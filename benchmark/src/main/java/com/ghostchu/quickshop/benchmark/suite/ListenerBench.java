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

  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) throws Exception {

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

    // display-item chunk-entrance resend: what one CHUNK_DATA packet costs per spawned
    // shop display of that chunk (applicability event + sender registration + the
    // packet sequence). The R22 candidate routes through the unified
    // VirtualDisplayItemManager.resendChunkDisplays (one destroy-spawn-meta sequence);
    // the baseline listener body additionally issued an explicit destroy packet right
    // before sendFakeItem — which itself opens with a destroy — so each display cost
    // one redundant packet + event dispatch. The unified method is probed reflectively
    // so this source compiles against baseline jars too (R16 runtime-probe precedent).
    lenient().when(Env.server().getOnlinePlayers()).thenReturn(java.util.List.of());
    lenient().when(Env.server().getViewDistance()).thenReturn(10);
    final var displays = new com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItem<?>[4];
    for(int i = 0; i < displays.length; i++) {
      displays[i] = buildSpawnedDisplay(world);
    }

    // candidate-only unified entry point; baseline falls back to the historic
    // listener body inside the bench op
    java.lang.invoke.MethodHandle resend = null;
    com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItemManager resendTarget = null;
    try {
      final var method = com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItemManager.class
              .getMethod("resendChunkDisplays", org.bukkit.entity.Player.class, String.class, int.class, int.class);
      resend = java.lang.invoke.MethodHandles.publicLookup().unreflect(method);
      resendTarget = org.mockito.Mockito.mock(
              com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItemManager.class,
              org.mockito.Mockito.withSettings().stubOnly()
                      .defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
      injectField(resendTarget, "chunksMapping", new java.util.concurrent.ConcurrentHashMap<>());
      // raw Map: the value's generic VirtualDisplayItem element type is not nameable
      // from this module without a package-private constructor reference
      ((java.util.Map)resendTarget.getChunksMapping()).put(new com.ghostchu.quickshop.shop.SimpleShopChunk("world", 1, 2),
                                                           new java.util.ArrayList<>(java.util.List.of(displays)));
    } catch(final NoSuchMethodException absentInBaseline) {
      // baseline jar predates resendChunkDisplays: the op below measures the historic
      // listener body instead
    }

    final java.lang.invoke.MethodHandle candidateResend = resend;
    final com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItemManager candidateTarget = resendTarget;
    final org.bukkit.entity.Player entering = Env.pin(Env.hotMock(org.bukkit.entity.Player.class));
    lenient().when(entering.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(new byte[]{2}));

    harness.bench("listener/displayResend", ctx -> {
      ctx.index++;
      if(candidateResend != null) {
        try {
          candidateResend.invokeExact(candidateTarget, entering, "world", 1, 2);
        } catch(final Throwable t) {
          throw new IllegalStateException("resendChunkDisplays failed", t);
        }
      } else {
        // the baseline CHUNK_DATA listener body (in-map variant), per display
        for(final var display : displays) {
          com.ghostchu.quickshop.benchmark.BenchHarness.consume(display.isApplicableForPlayer(entering));
          display.getPacketSenders().add(entering.getUniqueId());
          display.sendDestroyPacket(entering);
          display.sendFakeItem(entering);
        }
      }
    });

    benchInventoryCheck(harness);
  }

  // guard-item scan on InventoryOpenEvent (DisplayProtectionListener): virtual
  // display backend (default) can never produce guard stacks, so the R24 candidate
  // returns after one mode check while the baseline walks every slot with a config
  // lookup per item. Same body on both sides — behavior differs by jar.
  private static void benchInventoryCheck(final com.ghostchu.quickshop.benchmark.BenchHarness harness) {

    Env.setConfig("shop.display-type", 2);
    lenient().when(Env.plugin().isDisplayEnabled()).thenReturn(true);
    final var checkInv = Env.pin(Env.hotMock(com.ghostchu.quickshop.api.inventory.InventoryWrapper.class));
    lenient().when(checkInv.getHolder()).thenReturn(Env.pin(Env.hotMock(org.bukkit.inventory.InventoryHolder.class)));
    final var checkIter = Env.pin(Env.hotMock(com.ghostchu.quickshop.api.inventory.InventoryWrapperIterator.class));
    final int[] cursor = {0};
    lenient().when(checkIter.hasNext()).thenAnswer(inv -> {
      if(cursor[0] >= 54) {
        cursor[0] = 0;
        return false;
      }
      return true;
    });
    lenient().when(checkIter.next()).thenAnswer(inv -> {
      cursor[0]++;
      return Env.pin(Env.hotMock(org.bukkit.inventory.ItemStack.class));
    });
    lenient().when(checkInv.iterator()).thenReturn(checkIter);
    harness.bench("listener/inventoryCheck", ctx -> {
      ctx.index++;
      com.ghostchu.quickshop.util.Util.inventoryCheck(checkInv);
    });
  }

  /**
   * A fully spawned VirtualDisplayItem over mock shop/factory/manager surfaces. The
   * packet factory hands out three marker packets (velocity stays null on both sides,
   * matching the packetevents factories) and the display manager mock is a stub-only
   * hot mock with a real entity-id map injected.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItem<?> buildSpawnedDisplay(final World world) throws Exception {

    final var manager = Env.pin(Env.hotMock(com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItemManager.class));
    when(manager.generateEntityId()).thenReturn(9000);
    lenient().when(manager.packetHandler()).thenReturn(null);
    injectField(manager, "shopEntities", new java.util.concurrent.ConcurrentHashMap());

    final var factory = Env.pin(Env.hotMock(com.ghostchu.quickshop.api.shop.display.PacketFactory.class));
    when(factory.createSpawnPacket(org.mockito.ArgumentMatchers.anyInt(), any(Location.class))).thenReturn(new Object());
    when(factory.createMetaDataPacket(org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(new Object());
    when(factory.createDestroyPacket(org.mockito.ArgumentMatchers.anyInt())).thenReturn(new Object());
    when(factory.createVelocityPacket(org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);

    final var item = Env.pin(Env.hotMock(org.bukkit.inventory.ItemStack.class));
    lenient().when(item.clone()).thenReturn(item);
    lenient().when(item.asOne()).thenReturn(item);
    lenient().when(item.getEnchantments()).thenReturn(java.util.Map.of());
    lenient().when(item.getAmount()).thenReturn(1);
    lenient().when(item.getMaxStackSize()).thenReturn(64);

    final var shop = Env.pin(Env.hotMock(com.ghostchu.quickshop.api.shop.Shop.class));
    when(shop.getShopId()).thenReturn((long)(Math.random() * Long.MAX_VALUE));
    when(shop.getItem()).thenReturn(item);
    when(shop.bukkitLocation()).thenReturn(new Location(world, 16, 64, 32));
    lenient().when(shop.isLoaded()).thenReturn(true);

    // the constructor is package-private (manager-factory creation only) on both the
    // baseline and candidate jars, so construction goes through reflection; every
    // method the bench loop calls is public and invoked directly
    final var displayClass = com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItem.class;
    final var constructor = displayClass.getDeclaredConstructor(
            com.ghostchu.quickshop.shop.display.virtual.VirtualDisplayItemManager.class,
            com.ghostchu.quickshop.api.shop.display.PacketFactory.class,
            com.ghostchu.quickshop.api.shop.Shop.class);
    constructor.setAccessible(true);
    final var display = constructor.newInstance(manager, factory, shop);
    display.spawn();
    return display;
  }

  /** Walks the class hierarchy for the field: mock instances are ByteBuddy subclasses,
   *  and the injected fields may be public (shopEntities) or private (chunksMapping). */
  private static void injectField(final Object target, final String field, final Object value) throws Exception {

    for(Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        final var declared = type.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
        return;
      } catch(final NoSuchFieldException deeper) {
        // walk up the hierarchy
      }
    }
    throw new NoSuchFieldException(field + " not found on " + target.getClass());
  }
}
