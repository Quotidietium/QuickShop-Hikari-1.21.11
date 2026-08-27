package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R28 item-name flag snapshots: every
 * {@link Util#getItemStackName} / {@link Util#getItemCustomName} call previously walked
 * the config tree twice (enchanted-book preference inside
 * {@link Util#useEnchantmentForEnchantedBook()} and the force-original-name gate).
 * Both flags are now refreshed by {@link Util#initialize()} — the registered reload
 * hook — so hot loops resolve names without any config access.
 */
class UtilItemNameSnapshotTest {

  private org.mockito.MockedStatic<Bukkit> bukkitStatic;
  private org.mockito.MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private YamlDocument config;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

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
    // Util.initialize's shop-blocks / stacksize loops walk these readers
    lenient().when(config.getStringList(anyString())).thenReturn(new java.util.ArrayList<>());

    final var platform = mock(com.ghostchu.quickshop.platform.Platform.class);
    lenient().when(platform.getTranslation(any(org.bukkit.inventory.ItemStack.class)))
            .thenReturn(net.kyori.adventure.text.Component.text("Diamond"));
    lenient().when(plugin.platform()).thenReturn(platform);

    final var reloadManager = mock(com.ghostchu.simplereloadlib.ReloadManager.class);
    lenient().when(plugin.getReloadManager()).thenReturn(reloadManager);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private void initializeFlags() {

    // defaults: absent keys read false on both flags
    Util.initialize();
  }

  private org.bukkit.inventory.ItemStack plainDiamond() {

    final var item = mock(org.bukkit.inventory.ItemStack.class);
    lenient().when(item.getType()).thenReturn(org.bukkit.Material.DIAMOND);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    return item;
  }

  @Test
  void repeatedNameResolutionsMakeNoConfigReads() {

    initializeFlags();
    final var item = plainDiamond();
    clearInvocations(config);

    for(int i = 0; i < 100; i++) {
      Util.getItemStackName(item);
    }

    verify(config, times(0)).getBoolean(anyString());
    assertEquals(net.kyori.adventure.text.Component.text("Diamond"), Util.getItemStackName(item));
  }

  @Test
  void forceOriginalNameFlagIsHonoredFromSnapshot() {

    configValues.put("shop.force-use-item-original-name", true);
    configValues.put("shop.use-enchantment-for-enchanted-book", true);
    initializeFlags();
    clearInvocations(config);

    // enchanted-book preference consults the snapshot and bails before any meta work
    // even for the matching material
    final var book = mock(org.bukkit.inventory.ItemStack.class);
    lenient().when(book.getType()).thenReturn(org.bukkit.Material.ENCHANTED_BOOK);
    assertEquals(net.kyori.adventure.text.Component.text("Diamond"), Util.getItemStackName(book));

    // resolution proceeds through the translation fallback for the meta-less stack
    // because both gates come from the constructor-time snapshot
    final var item = plainDiamond();
    assertEquals(net.kyori.adventure.text.Component.text("Diamond"), Util.getItemStackName(item));
    verify(config, times(0)).getBoolean(anyString());
  }
}
