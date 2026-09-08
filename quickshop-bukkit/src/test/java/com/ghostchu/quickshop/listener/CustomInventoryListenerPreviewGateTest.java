package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.holder.QuickShopPreviewGUIHolder;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the preview-GUI guard carried by the click and drag handlers after the dead
 * InventoryInteractEvent handler was removed: item movement inside a
 * QuickShopPreviewGUIHolder inventory must stay cancelled, ordinary inventories must
 * pass. (InventoryInteractEvent declares no HandlerList of its own — a handler for it
 * registers on InventoryEvent's shared list, which click/drag events never fire, so
 * only the two concrete handlers guard the preview.)
 */
class CustomInventoryListenerPreviewGateTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private CustomInventoryListener listener;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    listener = new CustomInventoryListener(plugin);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private InventoryClickEvent click(final InventoryHolder holder) {

    final Inventory inventory = mock(Inventory.class);
    lenient().when(inventory.getHolder(false)).thenReturn(holder);
    final InventoryClickEvent event = mock(InventoryClickEvent.class);
    when(event.getInventory()).thenReturn(inventory);
    lenient().when(event.isCancelled()).thenReturn(false);
    return event;
  }

  private InventoryDragEvent drag(final InventoryHolder holder) {

    final Inventory inventory = mock(Inventory.class);
    lenient().when(inventory.getHolder(false)).thenReturn(holder);
    final InventoryDragEvent event = mock(InventoryDragEvent.class);
    when(event.getInventory()).thenReturn(inventory);
    lenient().when(event.isCancelled()).thenReturn(false);
    return event;
  }

  @Test
  void clickInsidePreviewInventoryCancels() {

    final var event = click(mock(QuickShopPreviewGUIHolder.class));
    listener.invEvent(event);
    verify(event).setCancelled(true);
  }

  @Test
  void dragInsidePreviewInventoryCancels() {

    final var event = drag(mock(QuickShopPreviewGUIHolder.class));
    listener.invEvent(event);
    verify(event).setCancelled(true);
  }

  @Test
  void ordinaryInventoryClicksPassThrough() {

    final var click = click(mock(InventoryHolder.class));
    listener.invEvent(click);
    verify(click, never()).setCancelled(true);

    final var drag = drag(mock(InventoryHolder.class));
    listener.invEvent(drag);
    verify(drag, never()).setCancelled(true);
  }
}
