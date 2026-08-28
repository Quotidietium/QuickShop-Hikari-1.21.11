package com.ghostchu.quickshop.api.inventory;

import org.jetbrains.annotations.NotNull;

/**
 * Slot-level inverse journal for one inventory mutation phase.
 * <p>
 * Created by {@link InventoryWrapper#beginMutationJournal()} before the mutating calls
 * (e.g. {@link InventoryWrapper#removeItem}, {@link InventoryWrapper#addItem}). The
 * wrapper records every slot it is about to modify — the previous stack reference and its
 * pre-mutation amount — at write time, so no whole-inventory scan or per-slot clone ever
 * happens; {@link #capture()} simply freezes the journal and
 * {@link #restore()} undoes exactly the recorded slots. This replaces the
 * {@link InventoryWrapper#createSnapshot()}/{@link InventoryWrapper#restoreSnapshot(ItemStack[])}
 * pair around a mutation phase. Wrappers that cannot record writes simply keep reporting
 * {@link InventoryWrapper#supportsMutationJournal()} {@code false}; callers then fall back
 * to snapshots, so both paths must restore an equivalent pre-mutation state.
 */
public interface MutationJournal {

  /**
   * Freezes the journal: after this call no further mutations are recorded. Must be
   * called once after the phase finished — including failed phases, so partial mutations
   * can be undone. Calling it more than once is a no-op.
   */
  void capture();

  /**
   * Restores every recorded slot to its pre-mutation state.
   *
   * @return whether the restore succeeded (mirrors
   *         {@link InventoryWrapper#restoreSnapshot(ItemStack[])})
   */
  boolean restore();

  /**
   * The number of slots the journal will restore; zero when the phase mutated nothing.
   *
   * @return recorded slot count
   */
  int touchedSlots();

  /**
   * A no-op journal for wrappers that only need to satisfy the interface shape.
   */
  @NotNull
  static MutationJournal empty() {

    return new MutationJournal() {
      @Override
      public void capture() {

      }

      @Override
      public boolean restore() {

        return true;
      }

      @Override
      public int touchedSlots() {

        return 0;
      }
    };
  }
}
