package com.ghostchu.quickshop.shop.operation;

import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.MutationJournal;
import com.ghostchu.quickshop.api.operation.Operation;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logger.Log;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Operation to remove items
 */
public class RemoveItemOperation implements Operation {

  private final ItemStack item;
  private final int amount;
  private final InventoryWrapper inv;
  private final int itemMaxStackSize;
  private boolean committed;
  private boolean rollback;
  private ItemStack[] snapshot;
  private MutationJournal journal;

  /**
   * Constructor
   *
   * @param item   ItemStack to remove
   * @param amount Amount to remove
   * @param inv    The {@link InventoryWrapper} that remove from
   */
  public RemoveItemOperation(@NotNull final ItemStack item, final int amount, @NotNull final InventoryWrapper inv) {

    this.item = item.clone();
    this.amount = amount;
    this.inv = inv;
    this.itemMaxStackSize = Util.getItemMaxStackSize(item.getType());

  }

  @Override
  public boolean commit() {

    committed = true;
    // rollback insurance: a slot-level journal when the wrapper supports one, otherwise
    // the historic full-inventory snapshot. Both restore the pre-commit state of whatever
    // this operation mutated; the journal just never clones (it diffs at capture time).
    final boolean journaling = inv.supportsMutationJournal();
    if(journaling) {
      this.journal = inv.beginMutationJournal();
    } else {
      this.snapshot = inv.createSnapshot();
    }
    boolean success;
    try {
      success = removeItems();
    } finally {
      // freeze the journal even on failure/exception so partial mutations are undoable
      if(journaling) {
        journal.capture();
      }
    }
    return success;
  }

  private boolean removeItems() {

    int remains = amount;
    int lastRemains = -1;
    while(remains > 0) {
      final int stackSize = Math.min(remains, itemMaxStackSize);
      item.setAmount(stackSize);
      final int remainsSnap = remains;
      Log.debug(() -> "Committing remove item operation, remains: " + remainsSnap + ", stackSize: " + stackSize + ", target: " + item.getType());
      final Map<Integer, ItemStack> notFit = inv.removeItem(item.clone());
      if(notFit.isEmpty()) {
        remains -= stackSize;
      } else {
        remains -= stackSize - notFit.entrySet().iterator().next().getValue().getAmount();
      }
      if(remains == lastRemains) {
        return false;
      }
      lastRemains = remains;
    }
    return true;
  }

  @Override
  public boolean isCommitted() {

    return this.committed;
  }

  @Override
  public boolean isRollback() {

    return this.rollback;
  }

  @Override
  public boolean rollback() {

    rollback = true;
    if(journal != null) {
      return journal.restore();
    }
    return inv.restoreSnapshot(snapshot);
  }

}
