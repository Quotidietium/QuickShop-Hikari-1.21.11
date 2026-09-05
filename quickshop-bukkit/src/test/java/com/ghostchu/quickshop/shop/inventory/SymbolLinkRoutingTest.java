package com.ghostchu.quickshop.shop.inventory;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.common.util.JsonUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the symbol-link format discriminator: locate() historically ran a
 * full Gson parse (isJson) per call — a parse that fails with a JsonSyntaxException on
 * every new-format link, i.e. on every inventory location. The discriminator is now the
 * leading character: old-format links are Gson objects ('{'), new-format links are
 * "v;x;y;z;world". Routing must stay identical for both internally generated formats,
 * and corrupted input must keep failing with IllegalArgumentException.
 */
class SymbolLinkRoutingTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private World world;
  private Block block;
  private Inventory chestInventory;

  @BeforeEach
  void setUp() {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    final var plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));

    world = mock(World.class);
    when(world.getName()).thenReturn("world");
    bukkitStatic.when(() -> Bukkit.getWorld("world")).thenReturn(world);

    chestInventory = mock(Inventory.class);
    final var blockState = mock(BlockState.class,
            org.mockito.Mockito.withSettings().stubOnly().extraInterfaces(InventoryHolder.class));
    when(((InventoryHolder)blockState).getInventory()).thenReturn(chestInventory);
    block = mock(Block.class);
    when(block.getState(false)).thenReturn(blockState);
    lenient().when(world.getBlockAt(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(block);
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  @Test
  void testNewFormatLinkResolvesViaBlockPosition() {

    final BukkitInventoryWrapperManager manager = new BukkitInventoryWrapperManager();
    assertInstanceOf(BukkitInventoryWrapper.class, manager.locate("2;12;64;-30;world"));
    verify(world).getBlockAt(12, 64, -30);
  }

  @Test
  void testOldFormatLinkStillRoutesThroughGson() {

    final var holder = new BukkitInventoryWrapperManager.BlockHolder("world", 7, 65, 9);
    final String content = JsonUtil.standard().toJson(holder);
    final String oldLink = JsonUtil.standard().toJson(
            new BukkitInventoryWrapperManager.CommonHolder(
                    BukkitInventoryWrapperManager.HolderType.BLOCK, content));

    final BukkitInventoryWrapperManager manager = new BukkitInventoryWrapperManager();
    assertInstanceOf(BukkitInventoryWrapper.class, manager.locate(oldLink));
    verify(world).getBlockAt(7, 65, 9);
  }

  @Test
  void testCorruptedLinksKeepFailingWithIllegalArgument() {

    final BukkitInventoryWrapperManager manager = new BukkitInventoryWrapperManager();
    // '{'-prefixed junk previously failed the isJson parse and took the new-format path
    // (split failure); both routings end in IllegalArgumentException from locate's catch
    assertThrows(IllegalArgumentException.class, () -> manager.locate("{corrupted-not-json"));
    // non-'{' junk keeps failing in BlockPos deserialization
    assertThrows(IllegalArgumentException.class, () -> manager.locate("garbage"));
  }
}
