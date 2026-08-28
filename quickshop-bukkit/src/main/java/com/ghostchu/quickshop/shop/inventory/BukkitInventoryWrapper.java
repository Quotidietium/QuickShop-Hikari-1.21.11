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

  @Override
  public @NotNull Map<Integer, ItemStack> addItem(final ItemStack... itemStacks) {

    return inventory.addItem(itemStacks);
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

  @Override
  public boolean supportsMutationJournal() {

    return true;
  }

  @Override
  public @NotNull MutationJournal beginMutationJournal() {

    return new BukkitMutationJournal(this.inventory);
  }

  /**
   * Journal over the storage-contents mirrors: begin records the pre-mutation array plus
   * each slot's amount (mirrors are live, so amounts must be read immediately), capture
   * diffs against a fresh array and keeps only the touched slots, restore rewrites exactly
   * those slots. Detection covers both real CraftItemStack mirrors (equals includes the
   * amount) and in-place mutations of the very same instance (amount vs the recorded
   * pre-value), so no per-slot clone is ever taken.
   */
  private static final class BukkitMutationJournal implements MutationJournal {

    private final Inventory inventory;
    private final ItemStack[] before;
    private final int[] beforeAmounts;
    private int[] touchedSlots = new int[8];
    private int touchedCount;
    private boolean captured;

    private BukkitMutationJournal(final Inventory inventory) {

      this.inventory = inventory;
      this.before = inventory.getStorageContents();
      this.beforeAmounts = new int[before.length];
      for(int i = 0; i < before.length; i++) {
        beforeAmounts[i] = before[i] == null? -1 : before[i].getAmount();
      }
    }

    @Override
    public void capture() {

      if(captured) {
        return;
      }
      captured = true;
      final ItemStack[] after = inventory.getStorageContents();
      for(int i = 0; i < before.length && i < after.length; i++) {
        if(slotChanged(i, after[i])) {
          if(touchedCount == touchedSlots.length) {
            touchedSlots = java.util.Arrays.copyOf(touchedSlots, touchedSlots.length * 2);
          }
          touchedSlots[touchedCount++] = i;
        }
      }
    }

    private boolean slotChanged(final int slot, final ItemStack current) {

      final ItemStack original = before[slot];
      if(original == current) {
        // same instance: an in-place amount mutation is only visible against the recorded
        // pre-mutation amount (mock stacks use identity equals, real mirrors fast-path it)
        return original != null && original.getAmount() != beforeAmounts[slot];
      }
      return !Objects.equals(original, current);
    }

    @Override
    public boolean restore() {

      for(int k = 0; k < touchedCount; k++) {
        final int slot = touchedSlots[k];
        final ItemStack original = before[slot];
        if(original == null) {
          inventory.setItem(slot, null);
        } else {
          original.setAmount(beforeAmounts[slot]);
          inventory.setItem(slot, original);
        }
      }
      return true;
    }

    @Override
    public int touchedSlots() {

      return touchedCount;
    }
  }

  @Override
  public void setContents(final ItemStack[] itemStacks) {

    inventory.setStorageContents(itemStacks);
  }

  @Override
  public String toString() {

    final Map<String, Object> map = new HashMap<>();
    map.put("inventory", inventory.toString());
    map.put("inventoryType", inventory.getClass().getName());
    return JsonUtil.getGson().toJson(map);
  }
}
