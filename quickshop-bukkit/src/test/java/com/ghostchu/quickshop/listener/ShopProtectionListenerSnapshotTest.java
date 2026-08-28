package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * Regression tests for the R32 protection-listener snapshots: {@code protect.entity}
 * gates an event that fires server-wide and must not walk the config tree before its
 * cheap check, and {@code protect.explode} used to be re-read once per block inside
 * every explosion loop. Both flip through init()/reloadModule().
 */
class ShopProtectionListenerSnapshotTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private YamlDocument config;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();
  private SimpleShopManager shopManager;
  private ShopProtectionListener listener;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.getReloadManager())
            .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getBoolean(anyString())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Boolean bool && bool;
    });
    lenient().when(config.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      if(value instanceof final Boolean bool) {
        return bool;
      }
      return inv.getArgument(1, Boolean.class);
    });
    lenient().when(plugin.getConfig()).thenReturn(config);

    shopManager = mock(SimpleShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private EntityChangeBlockEvent entityChangeAt(final int x) {

    final Location loc = new Location(null, x, y(x), z(x));
    final Block block = mock(Block.class);
    lenient().when(block.getLocation()).thenReturn(loc);
    final EntityChangeBlockEvent event = mock(EntityChangeBlockEvent.class);
    when(event.getBlock()).thenReturn(block);
    return event;
  }

  private int y(final int seed) {

    return 60 + seed;
  }

  private int z(final int seed) {

    return 100 + seed;
  }

  private BlockExplodeEvent explosionWithShops(final int hitCount) {

    final List<Block> blocks = new ArrayList<>();
    for(int i = 0; i < hitCount; i++) {
      final Location loc = new Location(null, i, 60, 200);
      final Block block = mock(Block.class);
      lenient().when(block.getLocation()).thenReturn(loc);
      blocks.add(block);
      lenient().when(shopManager.getShopIncludeAttached(loc)).thenReturn(mock(Shop.class));
    }
    final BlockExplodeEvent event = mock(BlockExplodeEvent.class);
    when(event.blockList()).thenReturn(blocks);
    return event;
  }

  @Test
  void entityGateTrueCancelsShopBlockChange() {

    configValues.put("protect.entity", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);
    final var event = entityChangeAt(1);
    lenient().when(shopManager.getShopIncludeAttached(any(Location.class))).thenReturn(mock(Shop.class));

    listener.onEntityBlockChange(event);

    verify(event).setCancelled(true);
  }

  @Test
  void entityGateFalseShortCircuitsBeforeTheLookup() {

    configValues.put("protect.entity", Boolean.FALSE);
    listener = new ShopProtectionListener(plugin);

    listener.onEntityBlockChange(entityChangeAt(2));

    verify(shopManager, never()).getShopIncludeAttached(any(Location.class));
  }

  @Test
  void entityGateFlipsThroughReloadWithoutReconstruction() {

    configValues.put("protect.entity", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);
    final var first = entityChangeAt(3);
    lenient().when(shopManager.getShopIncludeAttached(any(Location.class))).thenReturn(mock(Shop.class));
    listener.onEntityBlockChange(first);
    verify(shopManager, times(1)).getShopIncludeAttached(any(Location.class));

    configValues.put("protect.entity", Boolean.FALSE);
    listener.reloadModule();

    listener.onEntityBlockChange(entityChangeAt(4));
    // the second event never reached the lookup: snapshot taken at reload time
    verify(shopManager, times(1)).getShopIncludeAttached(any(Location.class));
  }

  @Test
  void explodeProtectCancelsOncePerEventWhenShopsAreHit() {

    configValues.put("protect.explode", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);
    final var event = explosionWithShops(3);

    listener.onBlockExplode(event);

    // the loop cancels once per hit shop-block (idempotent), never deletes
    verify(event, times(3)).setCancelled(true);
    verify(shopManager, never()).deleteShop(any(Shop.class));
  }

  @Test
  void explodeProtectFalseDeletesHitShopsAndReloadFlipsTheBehavior() {

    configValues.put("protect.explode", Boolean.FALSE);
    listener = new ShopProtectionListener(plugin);
    listener.onBlockExplode(explosionWithShops(2));
    verify(shopManager, times(2)).deleteShop(any(Shop.class));

    configValues.put("protect.explode", Boolean.TRUE);
    listener.reloadModule();
    final var after = explosionWithShops(1);
    listener.onBlockExplode(after);
    // still exactly two deletes: the reloaded snapshot cancelled instead
    verify(shopManager, times(2)).deleteShop(any(Shop.class));
    verify(after).setCancelled(true);
  }
}
