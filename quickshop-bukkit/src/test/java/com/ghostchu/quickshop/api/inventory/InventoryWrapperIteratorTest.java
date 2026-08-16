package com.ghostchu.quickshop.api.inventory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the single-snapshot bukkit inventory iterator: a full iteration
 * copies the storage array exactly once (the per-slot re-fetch made scans quadratic),
 * while setCurrent still writes back through the live inventory.
 */
class InventoryWrapperIteratorTest {

  private Inventory inventoryOver(final ItemStack[] contents) {

    final Inventory inventory = mock(Inventory.class);
    // mirror CraftInventory: every accessor call returns a fresh array copy
    when(inventory.getStorageContents()).thenAnswer(inv -> contents.clone());
    return inventory;
  }

  @Test
  void iterationCopiesStorageArrayExactlyOnce() {

    final ItemStack[] contents = new ItemStack[54];
    for(int i = 0; i < contents.length; i++) {
      contents[i] = mock(ItemStack.class);
    }
    final Inventory inventory = inventoryOver(contents);

    final InventoryWrapperIterator iterator = InventoryWrapperIterator.ofBukkitInventory(inventory);
    int seen = 0;
    while(iterator.hasNext()) {
      assertSame(contents[seen], iterator.next());
      seen++;
    }
    assertEquals(54, seen);
    verify(inventory, times(1)).getStorageContents();
  }

  @Test
  void setCurrentWritesBackToLiveInventoryAtCurrentIndex() {

    final ItemStack[] contents = {mock(ItemStack.class), mock(ItemStack.class), mock(ItemStack.class)};
    final Inventory inventory = inventoryOver(contents);

    final InventoryWrapperIterator iterator = InventoryWrapperIterator.ofBukkitInventory(inventory);
    iterator.next();// index 0
    iterator.next();// index 1 — current

    final ItemStack replacement = mock(ItemStack.class);
    iterator.setCurrent(replacement);

    final ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
    verify(inventory).setStorageContents(captor.capture());
    final ItemStack[] written = captor.getValue();
    assertEquals(3, written.length);
    assertSame(replacement, written[1]);
    assertSame(contents[0], written[0]);
    assertSame(contents[2], written[2]);
  }

  @Test
  void removeClearsTheLiveSlot() {

    final ItemStack[] contents = {mock(ItemStack.class)};
    final Inventory inventory = inventoryOver(contents);

    final InventoryWrapperIterator iterator = InventoryWrapperIterator.ofBukkitInventory(inventory);
    iterator.next();
    iterator.remove();

    final ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
    verify(inventory).setStorageContents(captor.capture());
    assertNull(captor.getValue()[0]);
  }

  @Test
  void exhaustionThrowsNoSuchElement() {

    final Inventory inventory = inventoryOver(new ItemStack[]{mock(ItemStack.class)});
    final InventoryWrapperIterator iterator = InventoryWrapperIterator.ofBukkitInventory(inventory);
    iterator.next();
    assertThrows(NoSuchElementException.class, iterator::next);
  }

  @Test
  void emptyInventoryIteratesNothing() {

    final Inventory inventory = inventoryOver(new ItemStack[0]);
    final InventoryWrapperIterator iterator = InventoryWrapperIterator.ofBukkitInventory(inventory);
    int seen = 0;
    while(iterator.hasNext()) {
      iterator.next();
      seen++;
    }
    assertEquals(0, seen);
    verify(inventory, times(1)).getStorageContents();
  }
}
