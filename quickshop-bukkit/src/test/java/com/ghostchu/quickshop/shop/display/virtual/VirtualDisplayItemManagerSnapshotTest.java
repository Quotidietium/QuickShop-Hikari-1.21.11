package com.ghostchu.quickshop.shop.display.virtual;

import com.ghostchu.quickshop.QuickShop;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Regression tests for the R32 packet-flag snapshots: {@code allowEnchants()} and
 * {@code useItemName()} were fresh config reads on every display spawn/meta rebuild;
 * they now serve volatiles refreshed through load()/reloadModule() (the manager is
 * reload-manager-registered since R32). CALLS_REAL_METHODS plus reflective wiring
 * replicates post-load state without executing the packet-handler constructor.
 */
class VirtualDisplayItemManagerSnapshotTest {

  private VirtualDisplayItemManager manager;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() throws Exception {

    final QuickShop plugin = mock(QuickShop.class);
    final YamlDocument config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getBoolean(anyString())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Boolean bool && bool;
    });
    lenient().when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      if(value instanceof final Boolean bool) {
        return bool;
      }
      return inv.getArgument(1, Boolean.class);
    });
    lenient().when(plugin.getConfig()).thenReturn(config);

    manager = mock(VirtualDisplayItemManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    setField(manager, "plugin", plugin);
    // mirrors the state load()/refreshFlagSnapshots establishes before the manager is
    // ever exposed (constructor order: load() runs before any spawn path can exist)
    setField(manager, "allowEnchantsSnapshot", true);
    setField(manager, "useItemNameSnapshot", false);
  }

  private static void setField(final Object target, final String name, final Object value) throws Exception {

    for(Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        final var declared = type.getDeclaredField(name);
        declared.setAccessible(true);
        declared.set(target, value);
        return;
      } catch(final NoSuchFieldException deeper) {
        // walk up
      }
    }
    throw new NoSuchFieldException(name);
  }

  @Test
  void defaultsHoldAndReloadFlipsBothFlags() {

    assertTrue(manager.allowEnchants());
    assertFalse(manager.useItemName());

    configValues.put("shop.display-allow-enchants", Boolean.FALSE);
    configValues.put("shop.display-item-use-name", Boolean.TRUE);
    manager.reloadModule();

    assertFalse(manager.allowEnchants());
    assertTrue(manager.useItemName());
  }

  @Test
  void absentKeysRestoreTheOriginalDefaultContractAfterReload() {

    // two-arg getBoolean(path,true) vs one-arg getBoolean(path): the fallback asymmetry
    // between the two flags must survive the snapshot swap exactly
    configValues.put("shop.display-allow-enchants", Boolean.FALSE);
    manager.reloadModule();
    assertFalse(manager.allowEnchants());

    configValues.remove("shop.display-allow-enchants");
    configValues.remove("shop.display-item-use-name");
    manager.reloadModule();

    assertTrue(manager.allowEnchants(), "explicit true default on allow-enchants");
    assertFalse(manager.useItemName(), "implicit false default on use-name");
  }
}
