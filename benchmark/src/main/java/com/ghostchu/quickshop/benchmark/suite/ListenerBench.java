package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.benchmark.Env;
import com.ghostchu.quickshop.listener.PlayerListener;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures the shop-search phase every player block click runs through when the clicked
 * block is not a shop (the overwhelmingly common case server-wide). The mock block keeps
 * CraftBukkit semantics visible to the code: block-data and state fetches are mock calls,
 * so eliminating a fetch eliminates a measurable unit of work.
 */
public final class ListenerBench {

  private ListenerBench() {

  }

  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) {

    final QuickShop plugin = Env.plugin();

    // Tag fields initialize lazily through Bukkit.getTag -> Bukkit.server; hand out
    // isTagged->false mocks so isWallSign behaves like a non-sign material
    lenient().when(Env.server().getTag(anyString(), any(org.bukkit.NamespacedKey.class), any(Class.class)))
            .thenAnswer(inv -> mock(Tag.class));

    final SimpleShopManager shopManager = mock(SimpleShopManager.class);
    when(plugin.getShopManager()).thenReturn(shopManager);
    when(shopManager.getShop(any(Location.class))).thenReturn(null);

    final PlayerListener listener = new PlayerListener(plugin);

    final World world = com.ghostchu.quickshop.benchmark.Env.pin(mock(World.class));
    when(world.getName()).thenReturn("world");
    final Player player = mock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(new byte[]{1}));

    final Block dirt = mock(Block.class);
    when(dirt.getType()).thenReturn(Material.DIRT);
    when(dirt.getWorld()).thenReturn(world);
    when(dirt.getLocation()).thenReturn(new Location(world, 10, 64, 10));
    when(dirt.getState(false)).thenReturn(mock(BlockState.class));

    final Block chest = mock(Block.class);
    when(chest.getType()).thenReturn(Material.CHEST);
    when(chest.getWorld()).thenReturn(world);
    when(chest.getLocation()).thenReturn(new Location(world, 20, 64, 20));
    when(chest.getState(false)).thenReturn(mock(BlockState.class,
            org.mockito.Mockito.withSettings().extraInterfaces(org.bukkit.block.Container.class)));
    // plain non-chest data: the double-chest branch probes and declines
    lenient().when(chest.getBlockData()).thenReturn(mock(org.bukkit.block.data.BlockData.class));

    harness.bench("listener/searchShopMiss", ctx -> {
      ctx.index++;
      final var result = listener.searchShop(dirt, player);
      com.ghostchu.quickshop.benchmark.BenchHarness.consume(result.getValue());
    });

    harness.bench("listener/searchShopContainer", ctx -> {
      ctx.index++;
      final var result = listener.searchShop(chest, player);
      com.ghostchu.quickshop.benchmark.BenchHarness.consume(result.getValue());
    });
  }
}
