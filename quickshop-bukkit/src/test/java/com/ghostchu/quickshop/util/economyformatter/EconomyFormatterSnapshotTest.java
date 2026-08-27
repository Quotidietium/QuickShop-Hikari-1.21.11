package com.ghostchu.quickshop.util.economyformatter;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R29 price-format snapshots: the internal format fallback
 * (Vault empty/exception paths and disable-vault-format servers) renders the currency
 * symbol from a snapshot refreshed on reload — repeated formats make zero config reads.
 * Output equivalence (side, symbol, per-currency mapping) is pinned alongside.
 */
class EconomyFormatterSnapshotTest {

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
    lenient().when(plugin.getReloadManager())
            .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      if(value instanceof final Boolean bool) {
        return bool;
      }
      return inv.getArgument(1, Boolean.class);
    });
    lenient().when(config.getString(anyString(), any(String.class))).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      if(value instanceof final String str) {
        return str;
      }
      return inv.getArgument(1, String.class);
    });
    lenient().when(config.getStringList(anyString())).thenReturn(new java.util.ArrayList<>());
    lenient().when(plugin.getConfig()).thenReturn(config);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private void setSymbols() {

    configValues.put("shop.alternate-currency-symbol", "€");
    configValues.put("shop.currency-symbol-on-right", true);
  }

  @Test
  void outputEquivalenceSymbolSideAndMapping() {

    setSymbols();
    when(config.getStringList("shop.alternate-currency-symbol-list"))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of("gems;G", "credits;C")));

    // right side + mapped currency uses the mapping table over the global symbol
    final BuiltInEconomyFormatter builtIn = new BuiltInEconomyFormatter(plugin);
    assertEquals("12.34€", builtIn.getInternalFormat(12.34d, null));
    assertEquals("12.34G", builtIn.getInternalFormat(12.34d, "gems"));

    // left side flips via reload
    configValues.put("shop.currency-symbol-on-right", false);
    configValues.remove("shop.alternate-currency-symbol-list");
    when(config.getStringList("shop.alternate-currency-symbol-list"))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of()));
    builtIn.reloadModule();
    assertEquals("€12.34", builtIn.getInternalFormat(12.34d, null));
  }

  @Test
  void repeatedFormatsMakeNoConfigReads() {

    setSymbols();
    final BuiltInEconomyFormatter builtIn = new BuiltInEconomyFormatter(plugin);
    clearConfigInvocations();

    for(int i = 0; i < 50; i++) {
      builtIn.getInternalFormat(1.5d, null);
    }

    verify(config, times(0)).getString(anyString(), any(String.class));
    assertEquals("1.5€", builtIn.getInternalFormat(1.5d, null));
  }

  @Test
  void economyFormatterFallbackConsumesTheSnapshotToo() {

    setSymbols();
    // disable-vault-format routes the public path straight to the internal snapshot
    configValues.put("shop.disable-vault-format", true);
    final EconomyFormatter formatter = new EconomyFormatter(plugin);
    final var world = mock(org.bukkit.World.class);
    assertEquals("12.34€", formatter.format(12.34d, world, null));

    clearConfigInvocations();
    assertEquals("1.5€", formatter.format(1.5d, world, null));
    verify(config, times(0)).getString(anyString(), any(String.class));
  }

  @Test
  void reloadRefreshesTheSnapshotWithoutReconstruction() {

    setSymbols();
    final BuiltInEconomyFormatter builtIn = new BuiltInEconomyFormatter(plugin);

    configValues.put("shop.alternate-currency-symbol", "¥");
    builtIn.reloadModule();

    assertEquals("12.34¥", builtIn.getInternalFormat(12.34d, null));
  }

  private void clearConfigInvocations() {

    org.mockito.Mockito.clearInvocations(config);
  }
}
