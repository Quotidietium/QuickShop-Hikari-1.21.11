package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the world blacklist/whitelist snapshots: Util.isBlacklistWorld
 * previously materialized both config lists on every call (it sits under isValid →
 * canBeShop, paid per shop click, trade validation and runtime-uuid resolution). The
 * snapshots refresh through {@link Util#initialize()} — the registered reload hook —
 * keeping whitelist-precedence and reload freshness semantics identical.
 */
class UtilWorldBlacklistSnapshotTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private YamlDocument config;
  private final Map<String, List<String>> listValues = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    config = mock(YamlDocument.class);
    listValues.clear();
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getStringList(anyString()))
            .thenAnswer(inv -> new ArrayList<>(listValues.getOrDefault(inv.getArgument(0, String.class), List.of())));
    lenient().when(plugin.getConfig()).thenReturn(config);

    // Util.initialize touches AbstractDisplayItem.refreshConfigSnapshots(), whose class
    // init builds a NamespacedKey from the java-plugin (must be non-null and lowercase)
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");

    final var reloadManager = mock(com.ghostchu.simplereloadlib.ReloadManager.class);
    lenient().when(plugin.getReloadManager()).thenReturn(reloadManager);
  }

  @AfterEach
  void tearDown() {

    // leave the static snapshots in the universal default (empty) so later test classes
    // observe the same world-filtering behaviour as before this suite ran
    listValues.clear();
    Util.initialize();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  private World world(final String name) {

    final World world = mock(World.class);
    when(world.getName()).thenReturn(name);
    return world;
  }

  @Test
  void snapshottedChecksMakeNoConfigReads() {

    listValues.put("shop.blacklist-world", List.of("world_nether", "world_the_end"));
    Util.initialize();
    clearInvocations(config);

    assertTrue(Util.isBlacklistWorld(world("world_nether")));
    assertTrue(Util.isBlacklistWorld(world("world_the_end")));
    for(int i = 0; i < 50; i++) {
      assertFalse(Util.isBlacklistWorld(world("world")));
    }

    verify(config, times(0)).getStringList(anyString());
  }

  @Test
  void whitelistTakesPrecedenceOverBlacklist() {

    // a non-empty whitelist means every world not on it is blacklisted, even when the
    // blacklist also names the world — same precedence as the previous live reads
    listValues.put("shop.whitelist-world", List.of("world"));
    listValues.put("shop.blacklist-world", List.of("world"));
    Util.initialize();

    assertFalse(Util.isBlacklistWorld(world("world")));
    assertTrue(Util.isBlacklistWorld(world("world_other")));
  }

  @Test
  void reloadRefreshesTheSnapshots() {

    listValues.put("shop.blacklist-world", List.of());
    Util.initialize();
    assertFalse(Util.isBlacklistWorld(world("world_new")));

    // an admin edit followed by /qs reload re-runs initialize()
    listValues.put("shop.blacklist-world", List.of("world_new"));
    Util.initialize();
    assertTrue(Util.isBlacklistWorld(world("world_new")));
  }
}
