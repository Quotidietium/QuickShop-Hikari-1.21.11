package com.ghostchu.quickshop.database;

import cc.carm.lib.easysql.manager.SQLManagerImpl;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import org.h2.jdbcx.JdbcDataSource;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * H2 in-memory regression test for the offline-message cleanup: cleanMessageForPlayer with a
 * timestamp cutoff must only delete messages stored up to that moment, so messages written
 * while a flush is in flight are never dropped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseHelperMessagesTest {

  private SimpleDatabaseHelperV2 helper;
  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;

  @BeforeAll
  void setUp() throws Exception {

    final QuickShop plugin = mock(QuickShop.class);
    when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    final YamlDocument config = mock(YamlDocument.class);
    when(plugin.getConfig()).thenReturn(config);
    when(config.getBoolean(anyString(), anyBoolean())).thenReturn(true); //skip version check
    when(config.getBoolean(anyString())).thenReturn(true);

    bukkitStatic = mockStatic(Bukkit.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);

    final JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:qstestmessages;MODE=MYSQL;DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    h2.setPassword("");
    final SQLManagerImpl sqlManager = new SQLManagerImpl(h2, "test-sql-manager");
    sqlManager.executeSQL("SET MODE=MYSQL");
    sqlManager.setExecutorPool(QuickExecutor.getHikaricpExecutor());

    helper = new SimpleDatabaseHelperV2(plugin, sqlManager, "qs_");
  }

  @AfterAll
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private static void await(final CompletableFuture<?> future) throws Exception {

    future.get(10, TimeUnit.SECONDS);
  }

  @Test
  void cutoffCleanupKeepsMessagesStoredAfterTheCutoff() throws Exception {

    final UUID player = UUID.randomUUID();
    final long now = System.currentTimeMillis();

    await(helper.saveOfflineTransactionMessage(player, "{\"text\":\"old-1\"}", now - 60_000));
    await(helper.saveOfflineTransactionMessage(player, "{\"text\":\"old-2\"}", now - 30_000));
    await(helper.saveOfflineTransactionMessage(player, "{\"text\":\"recent\"}", now));

    await(helper.cleanMessageForPlayer(player, now - 10_000));

    final List<String> remaining = helper.selectPlayerMessages(player).get(10, TimeUnit.SECONDS);
    assertEquals(1, remaining.size(), "only the message stored after the cutoff may survive");
    assertEquals("{\"text\":\"recent\"}", remaining.getFirst());
  }

  @Test
  void selectPlayerMessagesOnlyReturnsThePlayersOwnMessages() throws Exception {

    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    await(helper.saveOfflineTransactionMessage(a, "{\"text\":\"for-a\"}", System.currentTimeMillis()));
    await(helper.saveOfflineTransactionMessage(b, "{\"text\":\"for-b\"}", System.currentTimeMillis()));

    final List<String> forA = helper.selectPlayerMessages(a).get(10, TimeUnit.SECONDS);
    assertEquals(1, forA.size());
    assertEquals("{\"text\":\"for-a\"}", forA.getFirst());
  }
}
