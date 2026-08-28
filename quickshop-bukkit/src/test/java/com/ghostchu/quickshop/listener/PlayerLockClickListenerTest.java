package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import com.ghostchu.quickshop.util.Util;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R32 lock-gate snapshot: every valid shop right-click
 * consults {@code shop.lock}; historically that was a config-tree walk per click,
 * now a snapshot flipped through init()/reloadModule(). With the gate open the
 * authorization probe is short-circuited away entirely (first operand false).
 */
class PlayerLockClickListenerTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Util> utilStatic;
  private QuickShop plugin;
  private PlayerLockClickListener listener;
  private Shop shop;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    utilStatic = mockStatic(Util.class);
    when(Util.canBeShop(any(Block.class))).thenReturn(true);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(plugin.getReloadManager())
            .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    final YamlDocument config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getBoolean(anyString())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Boolean bool && bool;
    });
    lenient().when(plugin.getConfig()).thenReturn(config);

    final var manager = mock(com.ghostchu.quickshop.shop.SimpleShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(manager);
    lenient().when(plugin.perm())
            .thenReturn(mock(com.ghostchu.quickshop.permission.PermissionManager.class));
    final var text = mock(com.ghostchu.quickshop.localization.text.SimpleTextManager.class);
    lenient().when(plugin.text()).thenReturn(text);
    final var message = mock(com.ghostchu.quickshop.localization.text.SimpleTextManager.Text.class);
    lenient().when(text.of(any(org.bukkit.command.CommandSender.class), anyString())).thenReturn(message);

    shop = mock(Shop.class);
    lenient().when(shop.playerAuthorize(any(UUID.class), any(BuiltInShopPermission.class))).thenReturn(false);
    lenient().when(manager.getShopIncludeAttached(any(Location.class))).thenReturn(shop);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
    utilStatic.close();
  }

  private PlayerInteractEvent rightClick(final UUID playerId) {

    final Block block = mock(Block.class);
    lenient().when(block.getLocation()).thenReturn(new Location(null, 5, 60, 5));
    final Player player = mock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(playerId);
    final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    lenient().when(event.getClickedBlock()).thenReturn(block);
    lenient().when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
    lenient().when(event.getPlayer()).thenReturn(player);
    return event;
  }

  @Test
  void lockTrueCancelsUnauthorizedRightClickBeforeInShopTracking() {

    configValues.put("shop.lock", Boolean.TRUE);
    listener = new PlayerLockClickListener(plugin);
    final UUID id = UUID.randomUUID();
    final PlayerInteractEvent event = rightClick(id);

    listener.onClick(event);

    verify(event).setCancelled(true);
    verify(shop).playerAuthorize(id, BuiltInShopPermission.ACCESS_INVENTORY);
    assertFalse(QuickShop.inShop.contains(id), "a locked-out click must not enter in-shop tracking");
  }

  @Test
  void lockFalseSkipsTheAuthorizeCallAndTracksThePlayer() {

    // absent key == false (original one-arg getBoolean semantics)
    listener = new PlayerLockClickListener(plugin);
    final UUID id = UUID.randomUUID();
    final PlayerInteractEvent event = rightClick(id);

    listener.onClick(event);

    verify(shop, never()).playerAuthorize(any(UUID.class), any(BuiltInShopPermission.class));
    verify(event, never()).setCancelled(true);
    assertTrue(QuickShop.inShop.contains(id), "pass-through clicks register in-shop tracking");

    QuickShop.inShop.remove(id);
  }

  @Test
  void lockSnapshotFlipsThroughReloadWithoutReconstruction() {

    configValues.put("shop.lock", Boolean.TRUE);
    listener = new PlayerLockClickListener(plugin);
    final UUID lockedId = UUID.randomUUID();
    final PlayerInteractEvent locked = rightClick(lockedId);
    listener.onClick(locked);
    verify(locked).setCancelled(true);
    verify(shop, times(1)).playerAuthorize(any(UUID.class), any(BuiltInShopPermission.class));

    configValues.remove("shop.lock");
    listener.reloadModule();

    final UUID freedId = UUID.randomUUID();
    final PlayerInteractEvent freed = rightClick(freedId);
    listener.onClick(freed);
    // open gate short-circuits before any probe: still exactly one authorize call,
    // the same click now passes through into tracking
    verify(shop, times(1)).playerAuthorize(any(UUID.class), any(BuiltInShopPermission.class));
    verify(freed, never()).setCancelled(true);
    assertTrue(QuickShop.inShop.contains(freedId));

    QuickShop.inShop.remove(freedId);
  }
}
