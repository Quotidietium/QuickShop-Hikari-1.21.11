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
            .thenAnswer(inv -> Env.hotMock(Tag.class));

    final SimpleShopManager shopManager = Env.hotMock(SimpleShopManager.class);
    when(plugin.getShopManager()).thenReturn(shopManager);
    when(shopManager.getShop(any(Location.class))).thenReturn(null);

    final PlayerListener listener = new PlayerListener(plugin);

    final World world = com.ghostchu.quickshop.benchmark.Env.pin(Env.hotMock(World.class));
    when(world.getName()).thenReturn("world");
    final Player player = Env.hotMock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(new byte[]{1}));

    final Block dirt = Env.hotMock(Block.class);
    when(dirt.getType()).thenReturn(Material.DIRT);
    when(dirt.getWorld()).thenReturn(world);
    when(dirt.getLocation()).thenReturn(new Location(world, 10, 64, 10));
    when(dirt.getState(false)).thenReturn(Env.hotMock(BlockState.class));

    final Block chest = Env.hotMock(Block.class);
    when(chest.getType()).thenReturn(Material.CHEST);
    when(chest.getWorld()).thenReturn(world);
    when(chest.getLocation()).thenReturn(new Location(world, 20, 64, 20));
    when(chest.getState(false)).thenReturn(mock(BlockState.class,
            org.mockito.Mockito.withSettings().stubOnly().extraInterfaces(org.bukkit.block.Container.class)));
    // plain non-chest data: the double-chest branch probes and declines
    lenient().when(chest.getBlockData()).thenReturn(Env.hotMock(org.bukkit.block.data.BlockData.class));

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

    // CHUNK_DATA display-item listener: coordinate peek over a realistic chunk-sized
    // buffer. The peek is O(1) in the buffer size — the baseline's wrapper+getColumn()
    // instead walks every chunk section's paletted storage (structurally proven by
    // ChunkPacketPeekTest; the full parse itself needs the live packet pipeline).
    // candidate-only case (runtime probe, R16 batch precedent): baseline jars lack the
    // peek class and simply skip it
    try {
      Class.forName("com.ghostchu.quickshop.shop.display.virtual.packet.packetevents.ChunkPacketPeek");
      final var api = org.mockito.Mockito.mock(com.github.retrooper.packetevents.PacketEventsAPI.class);
      final var nettyManager = org.mockito.Mockito.mock(com.github.retrooper.packetevents.netty.NettyManager.class);
      org.mockito.Mockito.when(api.getNettyManager()).thenReturn(nettyManager);
      org.mockito.Mockito.lenient().when(nettyManager.getByteBufOperator())
              .thenReturn(new io.github.retrooper.packetevents.impl.netty.buffer.ByteBufOperatorImpl());
      com.github.retrooper.packetevents.PacketEvents.setAPI(api);
      final io.netty.buffer.ByteBuf chunkBuffer = io.netty.buffer.Unpooled.buffer(8 + 256 * 1024);
      chunkBuffer.writeInt(-42);
      chunkBuffer.writeInt(1337);
      chunkBuffer.writeBytes(new byte[256 * 1024]);
      chunkBuffer.readerIndex(0);
      harness.bench("listener/chunkPacketPeek", ctx -> {
        ctx.index++;
        final var coords = com.ghostchu.quickshop.shop.display.virtual.packet.packetevents.ChunkPacketPeek.peek(chunkBuffer);
        if(coords.x() != -42 || coords.z() != 1337 || chunkBuffer.readerIndex() != 0) {
          throw new IllegalStateException("peek corrupted state");
        }
        com.ghostchu.quickshop.benchmark.BenchHarness.consume(coords);
      });
    } catch(final ClassNotFoundException absentInBaseline) {
      // baseline jar: peek class not present, case intentionally unregistered
    }
  }
}
