package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the click-path block-access reductions in searchShop: ordinary
 * (non shop-block) clicks must not fetch block data, and the container classification
 * uses the snapshot-free state. Chest-family blocks keep the double-chest lookup.
 */
class PlayerListenerSearchShopTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private PlayerListener listener;
  private Player player;
  private World world;

  @BeforeEach
  void setUp() throws java.io.IOException {

    plugin = mock(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-search-test").toFile());
    final YamlDocument config = mock(YamlDocument.class);
    lenient().when(config.getBoolean("shop.ignore-cancelled-interact-event", true)).thenReturn(true);
    lenient().when(config.getBoolean(any(String.class))).thenReturn(false);
    lenient().when(plugin.getConfig()).thenReturn(config);

    final SimpleShopManager shopManager = mock(SimpleShopManager.class);
    lenient().when(shopManager.getShop(any(Location.class))).thenReturn(null);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);

    // Tag interface fields initialize through Bukkit.getTag on first use; the static
    // mock intercepts that call, so stub it to hand out isTagged->false mocks
    bukkitStatic.when(() -> Bukkit.getTag(any(String.class), any(org.bukkit.NamespacedKey.class), any(Class.class)))
            .thenAnswer(inv -> mock(org.bukkit.Tag.class));

    listener = new PlayerListener(plugin);
    player = mock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());

    world = mock(World.class);
    lenient().when(world.getName()).thenReturn("world");
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  private Block block(final Material material) {

    final Block block = mock(Block.class);
    lenient().when(block.getType()).thenReturn(material);
    lenient().when(block.getWorld()).thenReturn(world);
    lenient().when(block.getLocation()).thenReturn(new Location(world, 10, 64, 10));
    lenient().when(block.getX()).thenReturn(10);
    lenient().when(block.getY()).thenReturn(64);
    lenient().when(block.getZ()).thenReturn(10);
    return block;
  }

  @Test
  void ordinaryBlockClickSkipsBlockDataFetch() {

    final Block dirt = block(Material.DIRT);
    lenient().when(dirt.getState(false)).thenReturn(mock(BlockState.class));

    final Map.Entry<com.ghostchu.quickshop.api.shop.Shop, com.ghostchu.quickshop.api.shop.interaction.InteractionClick> result =
            listener.searchShop(dirt, player);

    assertNull(result.getKey());
    assertEquals(com.ghostchu.quickshop.api.shop.interaction.InteractionClick.SHOPBLOCK, result.getValue());
    // the material gate must keep ordinary clicks off getBlockData entirely
    verify(dirt, never()).getBlockData();
    // container classification runs on the snapshot-free state
    verify(dirt).getState(false);
    verify(dirt, never()).getState();
  }

  @Test
  void chestBlockStillRunsDoubleChestLookup() {

    final Block chest = block(Material.CHEST);
    final BlockState state = mock(BlockState.class,
            org.mockito.Mockito.withSettings().extraInterfaces(org.bukkit.block.Container.class));
    lenient().when(((InventoryHolder)state).getInventory()).thenReturn(mock(org.bukkit.inventory.Inventory.class));
    lenient().when(chest.getState(false)).thenReturn(state);
    lenient().when(chest.getBlockData()).thenReturn(mock(org.bukkit.block.data.BlockData.class));
    final Block airNeighbour = block(Material.AIR);
    lenient().when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(airNeighbour);

    final Map.Entry<com.ghostchu.quickshop.api.shop.Shop, com.ghostchu.quickshop.api.shop.interaction.InteractionClick> result =
            listener.searchShop(chest, player);

    // chest container classification preserved
    assertEquals(com.ghostchu.quickshop.api.shop.interaction.InteractionClick.CONTAINER, result.getValue());
    assertNull(result.getKey());
    // chest-family blocks still exercise the double-chest branch (second half lookup)
    verify(chest).getBlockData();
  }

  @Test
  void cancelledInteractFlagComesFromCachedSnapshot() throws Exception {

    final Field field = PlayerListener.class.getDeclaredField("ignoreCancelledInteractEvent");
    field.setAccessible(true);
    assertTrue((boolean)field.get(listener), "constructor reads the config default");

    // config change + reload refreshes the snapshot
    final YamlDocument config = (YamlDocument)plugin.getConfig();
    when(config.getBoolean("shop.ignore-cancelled-interact-event", true)).thenReturn(false);
    listener.reloadModule();
    assertFalse((boolean)field.get(listener), "reloadModule must re-read the config");
  }
}
