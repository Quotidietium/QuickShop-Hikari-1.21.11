package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.benchmark.Env;
import com.ghostchu.quickshop.shop.ContainerShop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import com.ghostchu.simplereloadlib.ReloadManager;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.ghostchu.quickshop.benchmark.BenchHarness.consume;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures the in-memory shop lookup structures (the three-level world/chunk/location
 * map plus its auxiliary caches) with 10k registered shops backed by real ContainerShop
 * instances.
 */
public final class ShopLookupBench {

  private static final int SHOP_COUNT = 10_000;

  private ShopLookupBench() {

  }

  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) throws Exception {

    final var plugin = Env.plugin();
    when(plugin.getReloadManager()).thenReturn(Env.hotMock(ReloadManager.class));
    final SimpleShopManager manager = new SimpleShopManager(plugin);
    // shop-cache boxing reaches back into the plugin for the shop manager
    when(plugin.getShopManager()).thenReturn(manager);

    final World world = com.ghostchu.quickshop.benchmark.Env.pin(Env.hotMock(World.class));
    when(world.getName()).thenReturn("world");
    // isValid() resolves the block at the shop location and checks its type
    final org.bukkit.block.Block block = Env.hotMock(org.bukkit.block.Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getType()).thenReturn(org.bukkit.Material.CHEST);
    when(world.getBlockAt(org.mockito.ArgumentMatchers.any(Location.class)))
            .thenReturn(block);
    when(world.getBlockAt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(block);
    when(world.isChunkLoaded(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);

    final ContainerShop[] shops = new ContainerShop[SHOP_COUNT];
    final Location[] locations = new Location[SHOP_COUNT];
    for(int i = 0; i < SHOP_COUNT; i++) {
      final ContainerShop shop = Shops.create(world, i, 64, i, i);
      shop.setShopId(i + 1L);
      shops[i] = shop;
      locations[i] = shop.bukkitLocation();
      manager.registerShop(shop, false).join();
      // real servers have loaded shops registered here; handleLoading touches the world,
      // so the live set is populated directly
      manager.getLoadedShops().add(shop);
    }

    // runtime-uuid cache mirrors production behaviour: only recently used shops are baked
    for(int i = SHOP_COUNT - 50; i < SHOP_COUNT; i++) {
      manager.bakeShopRuntimeRandomUniqueIdCache(shops[i]);
    }

    final int[] permutation = ThreadLocalRandom.current().ints(0, SHOP_COUNT).distinct()
            .limit(SHOP_COUNT).toArray();
    final int[] missOffsets = {5, 9, 15, 21, 27, 33, 39, 45};

    harness.bench("lookup/getShopByLocation-hit", ctx -> {
      final int i = permutation[(int)(ctx.index++ % SHOP_COUNT)];
      consume(manager.getShop(locations[i].clone(), true));
    });

    harness.bench("lookup/getShopByLocation-miss", ctx -> {
      final int i = permutation[(int)(ctx.index++ % SHOP_COUNT)];
      final Location miss = new Location(world, locations[i].getBlockX() + missOffsets[(int)(ctx.index % missOffsets.length)], 64, locations[i].getBlockZ());
      consume(manager.getShop(miss, true));
    });

    harness.bench("lookup/getShopById", ctx -> {
      final int i = permutation[(int)(ctx.index++ % SHOP_COUNT)];
      consume(manager.getShop(i + 1L));
    });

    harness.bench("lookup/getShopByRuntimeUuid-cached", ctx -> {
      final int i = SHOP_COUNT - 50 + (int)(ctx.index++ % 50);
      consume(manager.getShopFromRuntimeRandomUniqueId(shops[i].getRuntimeRandomUniqueId()));
    });

    harness.bench("lookup/getShopByRuntimeUuid-uncached", ctx -> {
      final int i = permutation[(int)(ctx.index++ % (SHOP_COUNT - 50))];
      consume(manager.getShopFromRuntimeRandomUniqueId(shops[i].getRuntimeRandomUniqueId()));
    });

    harness.bench("lookup/getAllShopsByOwner", ctx -> {
      final int ownerIndex = (int)(ctx.index++ % 100);
      final var owner = shops[ownerIndex].getOwner();
      consume(manager.getAllShops(owner));
    });

    harness.bench("lookup/getAllShops", ctx -> {
      ctx.index++;
      consume(manager.getAllShops());
    });

    harness.bench("lookup/getShopsInChunk", ctx -> {
      final int i = permutation[(int)(ctx.index++ % SHOP_COUNT)];
      consume(manager.getShops("world", locations[i].getBlockX() >> 4, locations[i].getBlockZ() >> 4));
    });

    final List<ContainerShop> sample = new ArrayList<>(List.of(shops));
    consume(sample.size());
  }
}
