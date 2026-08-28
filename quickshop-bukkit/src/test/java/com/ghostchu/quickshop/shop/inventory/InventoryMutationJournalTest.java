package com.ghostchu.quickshop.shop.inventory;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperIterator;
import com.ghostchu.quickshop.api.inventory.MutationJournal;
import com.ghostchu.quickshop.api.shop.ItemMatcher;
import com.ghostchu.quickshop.shop.operation.AddItemOperation;
import com.ghostchu.quickshop.shop.operation.RemoveItemOperation;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the journal-based inventory operation layer: the slot-level
 * removeItem override must mirror the interface default's algorithm (matcher-driven
 * matching, cross-stack scan continuation, leftover map) while writing only touched
 * slots, and the mutation journal must restore exactly the touched slots — no
 * createSnapshot/restoreSnapshot round-trip on the success path.
 */
class InventoryMutationJournalTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private ItemMatcher matcher;
  private QuickShop plugin;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    plugin = mock(QuickShop.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    matcher = mock(ItemMatcher.class);
    // type-equality matcher, resolved once per removeItem call by both code paths
    when(matcher.matches(any(ItemStack.class), any(ItemStack.class))).thenAnswer(inv -> {
      final ItemStack toRemove = inv.getArgument(0, ItemStack.class);
      final ItemStack candidate = inv.getArgument(1, ItemStack.class);
      return toRemove != null && candidate != null && toRemove.getType() == candidate.getType();
    });
    when(plugin.getItemMatcher()).thenReturn(matcher);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  /** Stateful inventory mock over a live backing array: mirrors CraftInventory's
   *  copy-per-access arrays and per-slot setItem writes. */
  private static final class StatefulInventory {

    final ItemStack[] backing;
    final Inventory inventory;

    StatefulInventory(final ItemStack... contents) {

      this.backing = contents.clone();
      this.inventory = mock(Inventory.class);
      when(inventory.getStorageContents()).thenAnswer(inv -> backing.clone());
      when(inventory.getMaxStackSize()).thenReturn(64);
      org.mockito.Mockito.doAnswer(inv -> {
        backing[inv.getArgument(0, Integer.class)] = inv.getArgument(1, ItemStack.class);
        return null;
      }).when(inventory).setItem(anyInt(), any());
    }

    BukkitInventoryWrapper wrapper() {

      return new BukkitInventoryWrapper(inventory);
    }
  }

  /** Stateful mock stack: amount tracks setAmount, clone produces a fresh copy. */
  private static ItemStack stack(final Material material, final int amount) {

    final ItemStack item = mock(ItemStack.class);
    final int[] amt = {amount};
    when(item.getType()).thenReturn(material);
    when(item.getAmount()).thenAnswer(inv -> amt[0]);
    org.mockito.Mockito.doAnswer(inv -> {
      amt[0] = inv.getArgument(0, Integer.class);
      return null;
    }).when(item).setAmount(anyInt());
    when(item.getMaxStackSize()).thenReturn(64);
    when(item.isSimilar(any(ItemStack.class))).thenAnswer(inv -> {
      final ItemStack other = inv.getArgument(0, ItemStack.class);
      return other != null && other.getType() == material;
    });
    when(item.clone()).thenAnswer(inv -> stack(material, amt[0]));
    return item;
  }

  private static int amount(final ItemStack item) {

    return item.getAmount();
  }

  @Test
  void removeItemRemovesAcrossMatchingSlotsAndWritesOnlyTouchedSlots() {

    final StatefulInventory inv = new StatefulInventory(
            stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17),
            stack(Material.IRON_INGOT, 7), null);
    final BukkitInventoryWrapper wrapper = inv.wrapper();

    final ItemStack toRemove = stack(Material.DIAMOND, 40);
    final Map<Integer, ItemStack> leftover = wrapper.removeItem(toRemove);

    assertTrue(leftover.isEmpty(), "40 of 51 available diamonds must remove fully");
    assertEquals(0, amount(inv.backing[0]));
    assertEquals(0, amount(inv.backing[1]));
    assertEquals(11, amount(inv.backing[2]));
    assertEquals(7, amount(inv.backing[3]), "mismatching slots must stay untouched");
    assertNull(inv.backing[4]);
    // three matched slots — and only those — are written back
    verify(inv.inventory, times(3)).setItem(anyInt(), any());
    verify(inv.inventory).setItem(eq(0), any());
    verify(inv.inventory).setItem(eq(1), any());
    verify(inv.inventory).setItem(eq(2), any());
  }

  @Test
  void removeItemEquivalenceWithInterfaceDefaultAlgorithm() {

    // same scenario through the historic default path (iterator + setCurrent) to prove
    // the override keeps its exact semantics, including the cross-stack scan position
    final ItemStack[] contentsA = {stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17),
            stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17)};
    final ItemStack[] contentsB = {stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17),
            stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17)};
    final StatefulInventory a = new StatefulInventory(contentsA);
    final StatefulInventory b = new StatefulInventory(contentsB);

    // multi-stack removal: the second stack continues scanning where the first stopped
    final Map<Integer, ItemStack> leftoverA = defaultWrapper(a.inventory).removeItem(
            stack(Material.DIAMOND, 30), stack(Material.DIAMOND, 30));
    final Map<Integer, ItemStack> leftoverB = b.wrapper().removeItem(
            stack(Material.DIAMOND, 30), stack(Material.DIAMOND, 30));

    assertEquals(leftoverA.isEmpty(), leftoverB.isEmpty());
    for(int i = 0; i < 4; i++) {
      assertEquals(amount(contentsA[i]), amount(contentsB[i]), "slot " + i + " must match the default algorithm");
    }
    // 30+30 from 4x17: slots run 0,4 / 0,4
    assertEquals(0, amount(contentsB[0]));
    assertEquals(4, amount(contentsB[1]));
    assertEquals(0, amount(contentsB[2]));
    assertEquals(4, amount(contentsB[3]));
  }

  /** Fake wrapper that runs the interface default methods over the same stateful mock. */
  private InventoryWrapper defaultWrapper(final Inventory inventory) {

    final InventoryWrapper fake = mock(InventoryWrapper.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
    when(fake.iterator()).thenAnswer(inv -> InventoryWrapperIterator.ofBukkitInventory(inventory));
    return fake;
  }

  @Test
  void removeItemReturnsMutatedLeftoverWhenInsufficient() {

    final StatefulInventory inv = new StatefulInventory(
            stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17));
    final ItemStack toRemove = stack(Material.DIAMOND, 100);

    final Map<Integer, ItemStack> leftover = inv.wrapper().removeItem(toRemove);

    assertEquals(1, leftover.size());
    assertEquals(100 - 34, amount(leftover.get(0)), "leftover carries the remaining amount");
    assertEquals(0, amount(inv.backing[0]));
    assertEquals(0, amount(inv.backing[1]));
  }

  @Test
  void journalRecordsRemovalsAtWriteTimeAndRestoresExactlyThoseSlots() {

    final StatefulInventory inv = new StatefulInventory(
            stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17),
            stack(Material.IRON_INGOT, 7), null);
    final BukkitInventoryWrapper wrapper = inv.wrapper();

    final MutationJournal journal = wrapper.beginMutationJournal();
    assertTrue(wrapper.removeItem(stack(Material.DIAMOND, 40)).isEmpty());
    journal.capture();

    assertEquals(0, amount(inv.backing[0]));
    assertEquals(0, amount(inv.backing[1]));
    assertEquals(11, amount(inv.backing[2]));
    assertEquals(3, journal.touchedSlots(), "only the three drained slots are journaled");

    assertTrue(journal.restore());
    assertEquals(17, amount(inv.backing[0]), "pre-mutation amounts return");
    assertEquals(17, amount(inv.backing[1]));
    assertEquals(17, amount(inv.backing[2]));
    assertEquals(7, amount(inv.backing[3]), "untouched slots must not be rewritten");
    verify(inv.inventory, never()).setItem(eq(3), any());
    verify(inv.inventory, never()).setItem(eq(4), any());
    // the success path never touches snapshot machinery
    verify(inv.inventory, never()).getContents();
  }

  @Test
  void addItemMirrorsCraftBukkitLayoutAndJournalsBothMergeAndFill() {

    // upstream addItem: merge into partials first (slot order), then fill empties
    final StatefulInventory inv = new StatefulInventory(
            null, stack(Material.DIAMOND, 17), null);
    final BukkitInventoryWrapper wrapper = inv.wrapper();

    final MutationJournal journal = wrapper.beginMutationJournal();
    assertTrue(wrapper.addItem(stack(Material.DIAMOND, 50)).isEmpty());
    journal.capture();

    // 50: 47 merge into the partial (slot 1 grows to a full stack), 3 fill slot 0
    assertEquals(3, amount(inv.backing[0]));
    assertEquals(64, amount(inv.backing[1]));
    assertEquals(2, journal.touchedSlots(), "merge + fill are both journaled");

    assertTrue(journal.restore());
    assertNull(inv.backing[0], "filled slot returns to empty");
    assertEquals(17, amount(inv.backing[1]), "journal restores the merged-away partial amount");
    assertNull(inv.backing[2]);
  }

  @Test
  void addItemSplitsOversizedStacksAndReportsLeftovers() {

    // 100 diamonds into a 2-slot inventory: 64-fill + 36-fill across the empties
    final StatefulInventory inv = new StatefulInventory(null, null, stack(Material.IRON_INGOT, 7));
    final Map<Integer, ItemStack> leftover = inv.wrapper().addItem(stack(Material.DIAMOND, 100));

    assertTrue(leftover.isEmpty());
    assertEquals(64, amount(inv.backing[0]), "first placement caps at the inventory max stack");
    assertEquals(36, amount(inv.backing[1]));
    assertEquals(7, amount(inv.backing[2]), "dissimilar slots are never touched");

    // full inventory of dissimilar stacks: everything stays leftover
    final StatefulInventory full = new StatefulInventory(
            stack(Material.IRON_INGOT, 7), stack(Material.GOLD_INGOT, 7));
    final ItemStack wanted = stack(Material.DIAMOND, 5);
    final Map<Integer, ItemStack> unfit = full.wrapper().addItem(wanted);
    assertEquals(1, unfit.size());
    assertEquals(5, amount(unfit.get(0)), "leftover carries the unfittable amount");
    assertEquals(7, amount(full.backing[0]));
    assertEquals(7, amount(full.backing[1]));
  }

  @Test
  void removeItemOperationCommitsAndRollsBackThroughTheJournal() {

    final StatefulInventory inv = new StatefulInventory(
            stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17), stack(Material.DIAMOND, 17),
            stack(Material.IRON_INGOT, 7));
    final BukkitInventoryWrapper wrapper = inv.wrapper();

    final RemoveItemOperation operation = new RemoveItemOperation(
            stack(Material.DIAMOND, 64), 40, wrapper);
    assertTrue(operation.commit());
    assertEquals(0, amount(inv.backing[0]));
    assertEquals(0, amount(inv.backing[1]));
    assertEquals(11, amount(inv.backing[2]));
    assertEquals(7, amount(inv.backing[3]));

    assertTrue(operation.rollback());
    assertEquals(17, amount(inv.backing[0]), "journal rollback restores pre-commit amounts");
    assertEquals(17, amount(inv.backing[1]));
    assertEquals(17, amount(inv.backing[2]));
    // the success path never touches snapshot machinery
    verify(inv.inventory, never()).getContents();
  }

  @Test
  void addItemOperationRollbackClearsExactlyTheTouchedSlots() {

    final StatefulInventory inv = new StatefulInventory(
            null, stack(Material.DIAMOND, 17), null);
    final BukkitInventoryWrapper wrapper = inv.wrapper();

    final AddItemOperation operation = new AddItemOperation(stack(Material.DIAMOND, 64), 50, wrapper);
    assertTrue(operation.commit());
    // slot-level addItem: 47 merge into the partial (slot 1 to a full stack), 3 fill slot 0
    assertEquals(3, amount(inv.backing[0]));
    assertEquals(64, amount(inv.backing[1]));

    assertTrue(operation.rollback());
    assertNull(inv.backing[0], "filled slot returns to empty");
    assertEquals(17, amount(inv.backing[1]), "journal restores the merged-away partial amount");
    assertNull(inv.backing[2]);
    // the success path never touches snapshot machinery
    verify(inv.inventory, never()).getContents();
  }

  @Test
  void nonJournalWrappersKeepTheSnapshotFallback() {

    final InventoryWrapper legacy = mock(InventoryWrapper.class);
    when(legacy.supportsMutationJournal()).thenReturn(false);
    when(legacy.createSnapshot()).thenReturn(new ItemStack[0]);
    when(legacy.removeItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    when(legacy.restoreSnapshot(any())).thenReturn(true);

    final RemoveItemOperation operation = new RemoveItemOperation(stack(Material.DIAMOND, 64), 40, legacy);
    assertTrue(operation.commit());
    verify(legacy).createSnapshot();
    verify(legacy, never()).beginMutationJournal();

    assertTrue(operation.rollback());
    verify(legacy).restoreSnapshot(any());
  }
}
