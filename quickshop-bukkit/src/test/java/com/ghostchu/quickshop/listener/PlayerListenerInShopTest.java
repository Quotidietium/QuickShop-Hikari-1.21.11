package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression test for the inShop tracking queue: even when the inventory-close handler
 * bails out through its GH-303 / unloaded-chunk workarounds, the player UUID must be
 * removed from the unbounded queue - otherwise it leaks and slows every linear scan.
 */
class PlayerListenerInShopTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<MenuManager> menuManagerStatic;
  private PlayerListener listener;
  private UUID playerId;
  private Player player;

  @BeforeEach
  void setUp() {

    final QuickShop plugin = mock(QuickShop.class);
    when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    bukkitStatic = mockStatic(Bukkit.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);

    final MenuManager menuManager = mock(MenuManager.class);
    when(menuManager.inMenu(any(UUID.class))).thenReturn(false);
    menuManagerStatic = mockStatic(MenuManager.class);
    menuManagerStatic.when(MenuManager::instance).thenReturn(menuManager);

    listener = new PlayerListener(plugin);
    playerId = UUID.randomUUID();
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);

    QuickShop.inShop.remove(playerId);
  }

  @AfterEach
  void tearDown() {

    QuickShop.inShop.remove(playerId);
    menuManagerStatic.close();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  private InventoryCloseEvent closeEventWithInventoryLocation(final org.bukkit.Location inventoryLocation) {

    final InventoryCloseEvent event = mock(InventoryCloseEvent.class);
    when(event.getPlayer()).thenReturn(player);
    final Inventory inventory = mock(Inventory.class);
    when(inventory.getLocation()).thenReturn(inventoryLocation);
    when(event.getInventory()).thenReturn(inventory);
    return event;
  }

  @Test
  void nullInventoryLocationStillClearsTracking() {

    QuickShop.inShop.add(playerId);
    listener.onInventoryClose(closeEventWithInventoryLocation(null));
    assertFalse(QuickShop.inShop.contains(playerId), "GH-303 workaround must not strand the UUID in the queue");
  }

  @Test
  void unloadedChunkStillClearsTracking() {

    final org.bukkit.Location location = mock(org.bukkit.Location.class);
    //Util.isLoaded checks the chunk; a null chunk leaves the location "not loaded"
    when(location.getChunk()).thenReturn(null);
    when(location.getWorld()).thenReturn(null);
    QuickShop.inShop.add(playerId);
    listener.onInventoryClose(closeEventWithInventoryLocation(location));
    assertFalse(QuickShop.inShop.contains(playerId), "unloaded-chunk workaround must not strand the UUID in the queue");
  }
}
