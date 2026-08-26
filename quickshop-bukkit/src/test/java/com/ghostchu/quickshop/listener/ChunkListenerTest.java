package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
 * Regression tests for the R26 chunk load/unload fast paths: the guard-item orphan
 * sweep is a provable no-op while the virtual display backend is active (the same
 * constant-false predicate R24 proved for inventory slots), and shop-less chunks
 * must skip the load span entirely — the historic null guard never fired because
 * getShops always returns an empty map for unknown chunks.
 */
class ChunkListenerTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private SimpleShopManager shopManager;
  private ChunkListener listener;
  private Chunk chunk;
  private ChunkLoadEvent loadEvent;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(Bukkit.isPrimaryThread()).thenReturn(true);

    // AbstractDisplayItem's class initializer builds a NamespacedKey from
    // getJavaPlugin().getName() — both must answer before the first touch
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");

    shopManager = mock(SimpleShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
    lenient().when(plugin.getReloadManager())
            .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    lenient().when(shopManager.getShops(any(Chunk.class))).thenReturn(new HashMap<Location, Shop>());

    listener = new ChunkListener(plugin);

    final World world = mock(World.class);
    lenient().when(world.getName()).thenReturn("world");
    chunk = mock(Chunk.class);
    lenient().when(chunk.getWorld()).thenReturn(world);
    lenient().when(chunk.getX()).thenReturn(1);
    lenient().when(chunk.getZ()).thenReturn(2);
    lenient().when(chunk.getEntities()).thenReturn(new Entity[0]);

    loadEvent = mock(ChunkLoadEvent.class);
    lenient().when(loadEvent.isNewChunk()).thenReturn(false);
    lenient().when(loadEvent.getChunk()).thenReturn(chunk);

    displayType(2);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  /** Configures the display backend on the plugin instance AbstractDisplayItem binds. */
  private void displayType(final int type) {

    final YamlDocument config = mock(YamlDocument.class);
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
  void virtualModeSkipsTheEntitySweepOnChunkLoad() {

    lenient().when(chunk.getEntities()).thenAnswer(inv -> {
      throw new IllegalStateException("getEntities must not be reached in virtual mode");
    });
    listener.onChunkLoad(loadEvent);
    verify(chunk, never()).getEntities();
  }

  @Test
  void nonVirtualModeStillSweepsAndRemovesGuardItems() {

    displayType(0);
    final var guardStack = mock(ItemStack.class);
    when(guardStack.hasItemMeta()).thenReturn(true);
    final var meta = mock(ItemMeta.class);
    when(guardStack.getItemMeta()).thenReturn(meta);
    final var pdc = mock(PersistentDataContainer.class);
    when(meta.getPersistentDataContainer()).thenReturn(pdc);
    when(pdc.has(any(org.bukkit.NamespacedKey.class))).thenReturn(true);

    final Item guardEntity = mock(Item.class);
    when(guardEntity.getItemStack()).thenReturn(guardStack);
    final Entity plain = mock(Entity.class);
    when(chunk.getEntities()).thenReturn(new Entity[]{plain, guardEntity});

    listener.onChunkLoad(loadEvent);

    verify(chunk, times(1)).getEntities();
    verify(guardEntity).remove();
  }

  @Test
  void nonVirtualModeKeepsSweepingShoplessChunks() {

    displayType(0);
    // a shop-less chunk must still run the orphan sweep in non-virtual mode: stray
    // guard items from deleted shops have no registered shop to clean them up
    listener.onChunkLoad(loadEvent);
    verify(chunk, times(1)).getEntities();
    verify(shopManager, never()).loadShop(any(Shop.class));
  }

  @Test
  void shoplessChunkSkipsTheLoadSpanEntirely() {

    // virtual mode + no shops: nothing after the empty check may run
    listener.onChunkLoad(loadEvent);
    verify(shopManager, never()).loadShop(any(Shop.class));
    verify(chunk, never()).getWorld();
  }

  @Test
  void shopsInChunkAreLoaded() {

    final Shop shop = mock(Shop.class);
    final Map<Location, Shop> shops = new HashMap<>();
    shops.put(new Location(null, 0, 64, 0), shop);
    when(shopManager.getShops(any(Chunk.class))).thenReturn(shops);

    listener.onChunkLoad(loadEvent);
    verify(shopManager, times(1)).loadShop(shop);
  }

  @Test
  void unloadSkipsShoplessChunksAndUnloadsLoadedShops() {

    final ChunkUnloadEvent unloadEvent = mock(ChunkUnloadEvent.class);
    when(unloadEvent.getChunk()).thenReturn(chunk);
    listener.onChunkUnload(unloadEvent);
    verify(shopManager, never()).unloadShop(any(Shop.class));

    final Shop shop = mock(Shop.class);
    when(shop.isLoaded()).thenReturn(true);
    final Map<Location, Shop> shops = new HashMap<>();
    shops.put(new Location(null, 0, 64, 0), shop);
    when(shopManager.getShops(any(Chunk.class))).thenReturn(shops);
    listener.onChunkUnload(unloadEvent);
    verify(shopManager, times(1)).unloadShop(shop);
  }

  @Test
  void canProduceGuardItemsFollowsBackendSelection() {

    displayType(0);
    assertTrue(com.ghostchu.quickshop.shop.display.AbstractDisplayItem.canProduceGuardItems(),
               "non-virtual backend with displays enabled can produce guard stacks");
    displayType(2);
    assertFalse(com.ghostchu.quickshop.shop.display.AbstractDisplayItem.canProduceGuardItems(),
                "virtual backend never produces guard stacks");
    when(plugin.isDisplayEnabled()).thenReturn(false);
    assertFalse(com.ghostchu.quickshop.shop.display.AbstractDisplayItem.canProduceGuardItems(),
                "disabled displays never produce guard stacks");
  }
}
