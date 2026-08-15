package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.shop.InventoryTransaction;
import com.ghostchu.quickshop.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the inventory transaction callback contract and the
 * LIFO snapshot rollback behaviour.
 */
class SimpleInventoryTransactionTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Util> utilStatic;
  private InventoryWrapper chest;
  private InventoryWrapper player;
  private ItemStack item;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    final QuickShop plugin = mock(QuickShop.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    //SimpleInventoryTransaction resolves the plugin singleton at construction time
    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);

    //real ItemStacks need a live server (Registry init), so transactions are exercised
    //with a mocked stack; Util.serialize (YAML-based) is stubbed out for the log line
    utilStatic = mockStatic(Util.class, CALLS_REAL_METHODS);
    utilStatic.when(()->Util.serialize(any())).thenReturn("[item]");

    chest = mock(InventoryWrapper.class);
    player = mock(InventoryWrapper.class);
    when(chest.createSnapshot()).thenReturn(new ItemStack[0]);
    when(player.createSnapshot()).thenReturn(new ItemStack[0]);
    when(chest.restoreSnapshot(any())).thenReturn(true);
    when(player.restoreSnapshot(any())).thenReturn(true);

    item = mock(ItemStack.class);
    when(item.getType()).thenReturn(Material.DIRT);
    when(item.getAmount()).thenReturn(64);
    when(item.clone()).thenReturn(item);
  }

  @AfterEach
  void tearDown() {

    utilStatic.close();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  @Test
  void pluginCancelNotifiesOnFailed() {

    final boolean[] failed = {false};
    final InventoryTransaction.TransactionCallback callback = new InventoryTransaction.TransactionCallback() {
      @Override
      public boolean onCommit(final InventoryTransaction transaction) {

        return false;
      }

      @Override
      public void onFailed(final InventoryTransaction transaction) {

        failed[0] = true;
      }
    };

    final SimpleInventoryTransaction tx = SimpleInventoryTransaction.builder()
            .from(chest)
            .to(player)
            .item(item)
            .amount(64)
            .build();

    assertFalse(tx.commit(callback));
    assertTrue(failed[0], "onFailed must be invoked when a plugin cancels the commit");
    verify(chest, never()).removeItem(any(ItemStack[].class));
    verify(player, never()).addItem(any(ItemStack[].class));
  }

  @Test
  void successfulCommitMovesItemsInStackSizedBatches() {

    when(chest.removeItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    when(player.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

    final SimpleInventoryTransaction tx = SimpleInventoryTransaction.builder()
            .from(chest)
            .to(player)
            .item(item)
            .amount(128)
            .build();

    assertTrue(tx.failSafeCommit());

    final org.mockito.ArgumentCaptor<ItemStack[]> removed = org.mockito.ArgumentCaptor.forClass(ItemStack[].class);
    verify(chest, times(2)).removeItem(removed.capture());
    //128 items with max stack 64 must be transferred in two full stacks
    assertEquals(64, removed.getAllValues().get(0)[0].getAmount());
    assertEquals(64, removed.getAllValues().get(1)[0].getAmount());
    verify(player, times(2)).addItem(any(ItemStack[].class));
  }

  @Test
  void addFailureTriggersLifoSnapshotRollback() {

    //chest removal succeeds, player add fails partially -> everything must roll back by snapshot
    when(chest.removeItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    final Map<Integer, ItemStack> leftover = new HashMap<>();
    leftover.put(0, item);
    when(player.addItem(any(ItemStack[].class))).thenReturn(leftover);

    final SimpleInventoryTransaction tx = SimpleInventoryTransaction.builder()
            .from(chest)
            .to(player)
            .item(item)
            .amount(64)
            .build();

    assertFalse(tx.failSafeCommit());

    //LIFO: the player (to) snapshot is restored before the chest (from) snapshot
    final InOrder order = inOrder(player, chest);
    order.verify(player).restoreSnapshot(any());
    order.verify(chest).restoreSnapshot(any());
  }

  @Test
  void rollbackOfCommittedOperationsRestoresSnapshots() {

    when(chest.removeItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    when(player.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

    final SimpleInventoryTransaction tx = SimpleInventoryTransaction.builder()
            .from(chest)
            .to(player)
            .item(item)
            .amount(64)
            .build();

    assertTrue(tx.commit());
    tx.rollback(true);

    verify(player).restoreSnapshot(any());
    verify(chest).restoreSnapshot(any());
  }

  @Test
  void zeroAmountCommitDoesNothingButSucceeds() {

    when(chest.removeItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    when(player.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

    final SimpleInventoryTransaction tx = SimpleInventoryTransaction.builder()
            .from(chest)
            .to(player)
            .item(item)
            .amount(0)
            .build();

    //documents current behaviour: a zero-amount transaction is a no-op success;
    //the economy layer must guard against ever sending one (see SimpleTradeServiceTest)
    assertTrue(tx.commit());
    verify(chest, never()).removeItem(any(ItemStack[].class));
    verify(player, never()).addItem(any(ItemStack[].class));
  }
}
