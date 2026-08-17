package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.benchmark.Env;
import com.ghostchu.quickshop.database.bean.SimpleDataRecord;
import com.ghostchu.quickshop.obj.QUserImpl;
import com.ghostchu.quickshop.platform.Platform;
import com.ghostchu.quickshop.shop.ContainerShop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.ghostchu.quickshop.benchmark.BenchHarness.consume;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures the save-path serialization cost: {@code ContainerShop.createDataRecord()}
 * (which encodes the ItemStack twice on the baseline) and the param-map generation used
 * by the database dedup lookup.
 * <p>
 * {@code Platform.encodeStack} is stubbed with Base64 of a synthetic 400-byte payload to
 * approximate the real serializeAsBytes+Base64 cost profile without a live server; the
 * benchmark therefore captures the call-count reduction, which scales with whatever the
 * real encoder costs.
 */
public final class DataRecordBench {

  private static final int SHOP_COUNT = 256;

  private DataRecordBench() {

  }

  public static ContainerShop[] setup() {

    final QuickShop plugin = Env.plugin();

    // manager needed by ContainerShop.getTaxAccount() (casts to SimpleShopManager)
    final SimpleShopManager manager = mock(SimpleShopManager.class);
    when(plugin.getShopManager()).thenReturn(manager);
    when(manager.getCacheTaxAccount()).thenReturn(null);

    // platform with a realistic-cost encodeStack stub
    final Platform platform = mock(Platform.class);
    final String encoded = Base64.getEncoder().encodeToString(new byte[400]);
    when(platform.encodeStack(any(ItemStack.class))).thenAnswer(inv -> encoded);
    when(platform.decodeStack(any(String.class))).thenAnswer(inv -> {
      Base64.getDecoder().decode(inv.getArgument(0, String.class));
      return mock(ItemStack.class);
    });
    when(plugin.platform()).thenReturn(platform);

    final World world = com.ghostchu.quickshop.benchmark.Env.pin(mock(World.class));
    when(world.getName()).thenReturn("world");

    final ContainerShop[] shops = new ContainerShop[SHOP_COUNT];
    for(int i = 0; i < SHOP_COUNT; i++) {
      shops[i] = Shops.create(world, i * 3, 64, i * 7, i);
    }
    return shops;
  }

  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) {

    final ContainerShop[] shops = setup();
    final SimpleDataRecord[] records = new SimpleDataRecord[SHOP_COUNT];
    for(int i = 0; i < SHOP_COUNT; i++) {
      records[i] = shops[i].createDataRecord();
    }

    harness.bench("serialize/createDataRecord", ctx -> {
      final ContainerShop shop = shops[(ctx.index++) % SHOP_COUNT];
      consume(shop.createDataRecord());
    });

    harness.bench("serialize/generateParams", ctx -> {
      final SimpleDataRecord record = records[(ctx.index++) % SHOP_COUNT];
      consume(record.generateParams());
    });

    harness.bench("serialize/generateLookupParams", ctx -> {
      final SimpleDataRecord record = records[(ctx.index++) % SHOP_COUNT];
      consume(record.generateLookupParams());
    });
  }
}
