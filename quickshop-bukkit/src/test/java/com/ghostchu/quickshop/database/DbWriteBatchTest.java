package com.ghostchu.quickshop.database;

import cc.carm.lib.easysql.manager.SQLManagerImpl;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.QuickShopProvider;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for DbWriteBatcher: pending inventory-cache updates collapse to the
 * latest row per shop and land through one batch, offline messages append in order,
 * and both flushes chain correctly before reads.
 */
class DbWriteBatchTest {

  private static QuickShop plugin;
  private static SimpleDatabaseHelperV2 helper;
  private static DbWriteBatcher batcher;

  @BeforeAll
  static void boot() throws Exception {

    plugin = mock(QuickShop.class);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString(), any(Boolean.class))).thenReturn(true);
    lenient().when(plugin.getConfig()).thenReturn(config);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(plugin.getShopManager()).thenReturn(mock(com.ghostchu.quickshop.shop.SimpleShopManager.class));
    final var finder = mock(com.ghostchu.quickshop.api.shop.PlayerFinder.class);
    lenient().when(finder.name2Uuid(anyString(), any(Boolean.class), any()))
            .thenAnswer(inv -> UUID.nameUUIDFromBytes(inv.getArgument(0, String.class).getBytes()));
    lenient().when(finder.uuid2NameFuture(any(UUID.class), any(Boolean.class), any()))
            .thenReturn(CompletableFuture.completedFuture("user"));
    lenient().when(plugin.getPlayerFinder()).thenReturn(finder);
    final var platform = mock(com.ghostchu.quickshop.platform.Platform.class);
    lenient().when(platform.encodeStack(any(ItemStack.class))).thenReturn("aGVsbG8=");
    final ItemStack decoded = mock(ItemStack.class);
    lenient().when(decoded.getType()).thenReturn(Material.DIAMOND);
    lenient().when(platform.decodeStack(anyString())).thenReturn(decoded);
    lenient().when(plugin.platform()).thenReturn(platform);

    final Server server = mock(Server.class);
    when(server.isPrimaryThread()).thenReturn(true);
    when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
    final Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, server);

    final QuickShopProvider provider = mock(QuickShopProvider.class);
    when(provider.getApiInstance()).thenReturn(plugin);
    final var rsp = mock(org.bukkit.plugin.RegisteredServiceProvider.class);
    when(rsp.getProvider()).thenReturn(provider);
    final var rspPlugin = mock(org.bukkit.plugin.Plugin.class);
    lenient().when(rspPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(rsp.getPlugin()).thenReturn(rspPlugin);
    final var servicesManager = mock(org.bukkit.plugin.ServicesManager.class);
    lenient().when(servicesManager.getRegistration(QuickShopProvider.class)).thenReturn(rsp);
    when(server.getServicesManager()).thenReturn(servicesManager);

    final Field instanceField = QuickShop.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, plugin);

    final JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:qsdbwritebatch;MODE=MYSQL;DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    h2.setPassword("");
    final SQLManagerImpl sqlManager = new SQLManagerImpl(h2, "dbwritebatch-sql-manager");
    sqlManager.executeSQL("SET MODE=MYSQL");
    sqlManager.setExecutorPool(QuickExecutor.getHikaricpExecutor());
    helper = new SimpleDatabaseHelperV2(plugin, sqlManager, "qs_");
    lenient().when(plugin.getDatabaseHelper()).thenReturn(helper);
    batcher = new DbWriteBatcher(plugin, helper);
  }

  @AfterAll
  static void shutdown() throws Exception {

    final Field instanceField = QuickShop.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }

  @Test
  void cacheUpdatesCollapseToLatestPerShop() {

    final long shopA = 9101, shopB = 9102;
    // five rapid updates for shopA within one window + one for shopB
    for(int i = 1; i <= 5; i++) {
      batcher.offerInventoryCache(shopA, i * 10, i * 100);
    }
    batcher.offerInventoryCache(shopB, 7, 8);
    batcher.flushInventoryCacheAsync().join();

    final var cacheA = helper.queryInventoryCache(shopA).join();
    final var cacheB = helper.queryInventoryCache(shopB).join();
    assertEquals(50, cacheA.getSpace());
    assertEquals(500, cacheA.getStock());
    assertEquals(7, cacheB.getSpace());
    assertEquals(8, cacheB.getStock());
  }

  @Test
  void offlineMessagesAppendInOrder() {

    final UUID receiver = UUID.nameUUIDFromBytes("batch-recv".getBytes());
    for(int i = 0; i < 3; i++) {
      batcher.offerOfflineMessage(receiver, "msg-" + i, 1000L + i);
    }
    batcher.flushMessagesAsync().join();

    final List<String> msgs = helper.selectPlayerMessages(receiver).join();
    assertEquals(3, msgs.size());
    assertEquals("msg-0", msgs.get(0));
    assertEquals("msg-2", msgs.get(2));
  }

  @Test
  void directSingleWritesStillWork() {

    final long shop = 9103;
    helper.updateExternalInventoryProfileCache(shop, 1, 2).join();
    assertEquals(1, helper.queryInventoryCache(shop).join().getSpace());

    final UUID receiver = UUID.nameUUIDFromBytes("single-recv".getBytes());
    helper.saveOfflineTransactionMessage(receiver, "single", 42L).join();
    assertEquals(List.of("single"), helper.selectPlayerMessages(receiver).join());
  }

  @Test
  void emptyFlushIsNoOp() {

    batcher.flushInventoryCacheAsync().join();
    batcher.flushMessagesAsync().join();
  }
}
