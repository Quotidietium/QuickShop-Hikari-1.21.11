package com.ghostchu.quickshop.shop.inventory;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperIterator;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperManager;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperType;
import com.ghostchu.quickshop.api.inventory.MutationJournal;
import com.ghostchu.quickshop.common.util.JsonUtil;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class BukkitInventoryWrapper implements InventoryWrapper {

  private final Inventory inventory;
  private final InventoryWrapperManager manager;
  private final Supplier<String> eigenCodeProvider;
  private final String eigenCode;
  /**
   * Write-time journal attached between {@link #beginMutationJournal()} and
   * {@link MutationJournal#capture()}. Wrappers are documented main-thread/region-thread
   * only, so a plain field is safe; it is only ever non-null while one of this wrapper's
   * own mutating methods runs inside an active journaling phase.
   */
  private WriteTimeJournal activeJournal;

  public BukkitInventoryWrapper(@NotNull final Inventory inventory) {

    this(inventory, ()->null);
  }

  public BukkitInventoryWrapper(@NotNull final Inventory inventory, final Supplier<String> eigenCodeProvider) {

    this.inventory = inventory;
    this.manager = QuickShop.getInstance().getInventoryWrapperManager();
    this.eigenCodeProvider = eigenCodeProvider;
    this.eigenCode = eigenCodeProvider.get();
  }

  @Override
  public @NotNull InventoryWrapperIterator iterator() {

    return InventoryWrapperIterator.ofBukkitInventory(inventory);
  }

  @Override
  public void clear() {

    inventory.clear();
  }

  @Override
  public @NotNull ItemStack[] createSnapshot() {

    final ItemStack[] content = this.inventory.getContents();
    final ItemStack[] snapshot = new ItemStack[content.length];
    for(int i = 0; i < content.length; i++) {
      if(content[i] != null) {
        snapshot[i] = content[i].clone();
      } else {
        snapshot[i] = null;
      }

    }
    return snapshot;
  }

  @Override
  public @NotNull InventoryWrapperManager getWrapperManager() {

    return this.manager;
  }

  @Override
  public InventoryHolder getHolder() {

    return inventory.getHolder();
  }

  @Override
  public @NotNull InventoryWrapperType getInventoryType() {

    return InventoryWrapperType.BUKKIT;
  }

  @Override
  public @Nullable Location getLocation() {

    return inventory.getLocation();
  }

  @Override
  public boolean isValid() {

    if(this.inventory.getHolder(false) != null) {
      return true;
    } else {
      return this.inventory instanceof InventoryHolder;
    }
  }

  @Override
  public boolean isNeedUpdate() {

    return !Objects.equals(eigenCode, eigenCodeProvider.get());
  }

  @Override
  public boolean restoreSnapshot(@NotNull final ItemStack[] snapshot) {

    this.inventory.setContents(snapshot);
    return true;
  }

  /**
   * Slot-level mirror of CraftBukkit's {@code Inventory.addItem}: merge into partially
   * filled similar stacks first (slot order, capped by the stack's max size, mutated in
   * place exactly like the upstream mirror does), then fill empty slots (slot order,
   * capped by the inventory's max stack size), leftover map unchanged. Doing it at this
   * level lets a {@link MutationJournal} record every touched slot at write time — the
   * blind bulk call was unverifiable, which forced callers to snapshot the whole
   * inventory for rollback.
   */
  @Override
  public @NotNull Map<Integer, ItemStack> addItem(final ItemStack... itemStacks) {

    if(itemStacks.length == 0) {
      return Collections.emptyMap();
    }
    final ItemStack[] work = this.inventory.getStorageContents();
    // a non-positive max stack size (never produced by real inventories; it is the default
    // value of a partially-stubbed mock) would degenerate the placement loop below into
    // zero-sized stacks — treat it as the vanilla default instead
    final int rawMax = this.inventory.getMaxStackSize();
    final int inventoryMax = rawMax <= 0? 64 : rawMax;
    final Map<Integer, ItemStack> leftover = new HashMap<>();
    for(int i = 0; i < itemStacks.length; i++) {
      final ItemStack item = itemStacks[i];
      while(true) {
        // first partial stack: non-null, similar, with room left (upstream firstPartial)
        int firstPartial = -1;
        for(int slot = 0; slot < work.length; slot++) {
          final ItemStack stack = work[slot];
          if(stack != null && stack.getAmount() < stack.getMaxStackSize() && stack.isSimilar(item)) {
            firstPartial = slot;
            break;
          }
        }
        if(firstPartial == -1) {
          // no partial stack: first empty slot (upstream firstEmpty)
          int firstFree = -1;
          for(int slot = 0; slot < work.length; slot++) {
            if(work[slot] == null) {
              firstFree = slot;
              break;
            }
          }
          if(firstFree == -1) {
            leftover.put(i, item);
            break;
          }
          if(item.getAmount() > inventoryMax) {
            final ItemStack placed = item.clone();
            placed.setAmount(inventoryMax);
            journalWrite(firstFree, work[firstFree]);
            this.inventory.setItem(firstFree, placed);
            work[firstFree] = placed;
            item.setAmount(item.getAmount() - inventoryMax);
          } else {
            journalWrite(firstFree, work[firstFree]);
            this.inventory.setItem(firstFree, item);
            work[firstFree] = item;
            break;
          }
        } else {
          final ItemStack partial = work[firstPartial];
          final int amount = item.getAmount();
          final int partialAmount = partial.getAmount();
          final int maxAmount = partial.getMaxStackSize();
          journalWrite(firstPartial, partial);
          if(amount + partialAmount <= maxAmount) {
            // fully fits: grow the live stack in place, exactly like upstream
            partial.setAmount(amount + partialAmount);
            break;
          }
          partial.setAmount(maxAmount);
          item.setAmount(amount + partialAmount - maxAmount);
        }
      }
    }
    return leftover;
  }

  /**
   * Slot-level removal mirroring the interface default's algorithm exactly (matcher-driven
   * matching, scan position continues across the vararg stacks, leftover map carries the
   * mutated input stacks) while writing only the slots that actually changed: the default
   * paid a full getStorageContents copy plus a whole-inventory setStorageContents rewrite
   * for every matched slot. Storage indexes line up with {@code Inventory.setItem} indexes
   * because storage occupies the leading slots of every Bukkit inventory.
   */
  @Override
  public @NotNull Map<Integer, ItemStack> removeItem(final ItemStack... itemStacks) {

    if(itemStacks.length == 0) {
      return Collections.emptyMap();
    }
    final ItemStack[] contents = this.inventory.getStorageContents();
    final var matcher = QuickShop.getInstance().getItemMatcher();
    final Map<Integer, ItemStack> integerItemStackMap = new HashMap<>();
    int cursor = 0;
    RemoveProcess:
    for(int i = 0; i < itemStacks.length; i++) {
      final ItemStack itemStackToRemove = itemStacks[i];
      while(cursor < contents.length) {
        final ItemStack itemStack = contents[cursor];
        final int slot = cursor;
        cursor++;
        if(itemStack != null && matcher.matches(itemStackToRemove, itemStack)) {
          final int couldRemove = itemStack.getAmount();
          final int actuallyRemove = Math.min(itemStackToRemove.getAmount(), couldRemove);
          journalWrite(slot, itemStack);
          itemStack.setAmount(couldRemove - actuallyRemove);
          itemStackToRemove.setAmount(itemStackToRemove.getAmount() - actuallyRemove);
          this.inventory.setItem(slot, itemStack);
          if(itemStackToRemove.getAmount() == 0) {
            continue RemoveProcess;
          }
        }
      }
      if(itemStackToRemove.getAmount() != 0) {
        integerItemStackMap.put(i, itemStackToRemove);
      }
    }
    return integerItemStackMap;
  }

  private void journalWrite(final int slot, final ItemStack previous) {

    if(activeJournal != null) {
      activeJournal.record(slot, previous);
    }
  }

  @Override
  public void setContents(final ItemStack[] itemStacks) {

    inventory.setStorageContents(itemStacks);
  }

  @Override
  public boolean supportsMutationJournal() {

    return true;
  }

  @Override
  public @NotNull MutationJournal beginMutationJournal() {

    final WriteTimeJournal journal = new WriteTimeJournal();
    this.activeJournal = journal;
    return journal;
  }

  @Override
  public String toString() {

    final Map<String, Object> map = new HashMap<>();
    map.put("inventory", inventory.toString());
    map.put("inventoryType", inventory.getClass().getName());
    return JsonUtil.getGson().toJson(map);
  }

  /**
   * Journal whose records are appended by {@link #journalWrite} at the moment a slot is
   * about to be modified — the previous stack reference and its pre-mutation amount are
   * captured per touched slot only (no whole-inventory scan, no per-slot clone). Mirrors
   * are live, so the amount must be read before the in-place mutation; restore re-applies
   * the records in reverse (LIFO) so a slot touched twice rolls back to its original
   * state even when the journal holds the same mirror reference twice.
   */
  private final class WriteTimeJournal implements MutationJournal {

    private int[] slots = new int[8];
    private ItemStack[] previous = new ItemStack[8];
    private int[] previousAmounts = new int[8];
    private int count;
    private boolean captured;

    @Override
    public void capture() {

      captured = true;
      if(activeJournal == this) {
        activeJournal = null;
      }
    }

    void record(final int slot, final ItemStack prev) {

      if(captured) {
        return;
      }
      if(count == slots.length) {
        slots = Arrays.copyOf(slots, slots.length * 2);
        previous = Arrays.copyOf(previous, previous.length * 2);
        previousAmounts = Arrays.copyOf(previousAmounts, previousAmounts.length * 2);
      }
      slots[count] = slot;
      previous[count] = prev;
      previousAmounts[count] = prev == null? -1 : prev.getAmount();
      count++;
    }

    @Override
    public boolean restore() {

      for(int k = count - 1; k >= 0; k--) {
        final ItemStack prev = previous[k];
        if(prev == null) {
          inventory.setItem(slots[k], null);
        } else {
          prev.setAmount(previousAmounts[k]);
          inventory.setItem(slots[k], prev);
        }
      }
      return true;
    }

    @Override
    public int touchedSlots() {

      return count;
    }
  }
}
