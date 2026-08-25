package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperIterator;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R24 inventoryCheck fast path: with the virtual display
 * backend active (shop.display-type=2, the default), guard item stacks can never
 * exist, so the per-slot scan of every opened inventory is provably a no-op and must
 * be skipped wholesale; non-virtual modes keep scanning exactly as before.
 */
class UtilInventoryCheckTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private InventoryWrapper inventory;
  private InventoryWrapperIterator iterator;

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

    inventory = mock(InventoryWrapper.class);
    iterator = mock(InventoryWrapperIterator.class);
    when(inventory.getHolder()).thenReturn(mock(org.bukkit.inventory.InventoryHolder.class));
    when(inventory.iterator()).thenReturn(iterator);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private void displayType(final int type) {

    final YamlDocument config = mock(YamlDocument.class);
    // getNowUsing reads the one-arg overload; other config readers use defaults
    when(config.getInt("shop.display-type")).thenReturn(type);
    when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1, Integer.class));
    when(plugin.getConfig()).thenReturn(config);
    when(plugin.isDisplayEnabled()).thenReturn(true);
    // AbstractDisplayItem binds PLUGIN at class-init; if an earlier test class in this
    // JVM already loaded it against a different mock, stub that captured instance too
    try {
      final var field = Class.forName("com.ghostchu.quickshop.shop.display.AbstractDisplayItem")
              .getDeclaredField("PLUGIN");
      field.setAccessible(true);
      final QuickShop captured = (QuickShop)field.get(null);
      if(captured != null && captured != plugin) {
        when(captured.getConfig()).thenReturn(config);
        when(captured.isDisplayEnabled()).thenReturn(true);
      }
    } catch(final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void virtualModeSkipsTheWholeSlotScan() {

    displayType(2);
    Util.inventoryCheck(inventory);
    verify(inventory, never()).iterator();
    verify(iterator, never()).hasNext();
  }

  @Test
  void nonVirtualModeStillScansSlots() {

    displayType(0);
    when(iterator.hasNext()).thenReturn(true, false);
    when(iterator.next()).thenReturn(mock(ItemStack.class));
    Util.inventoryCheck(inventory);
    // one loop entry pass plus the terminating check
    verify(iterator, times(2)).hasNext();
    verify(iterator).next();
  }
}
