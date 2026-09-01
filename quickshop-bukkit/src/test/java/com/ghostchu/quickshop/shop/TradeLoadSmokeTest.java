package com.ghostchu.quickshop.shop;

import cc.carm.lib.easysql.manager.SQLManagerImpl;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.database.DatabaseHelper;
import com.ghostchu.quickshop.api.economy.EconomyManager;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import com.ghostchu.quickshop.database.SimpleDatabaseHelperV2;
import com.ghostchu.quickshop.economy.transaction.QSEconomyTransaction;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Load smoke test: runs a concurrent multi-"player" trade loop against the real economy
 * transaction stack (withdraw/deposit/tax/benefit + LIFO rollback) and a real H2 database,
 * then asserts global money conservation and a clean completion count. This is the runtime
 * evidence that the money path stays consistent under simultaneous multi-user load.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeLoadSmokeTest {

  private static final int THREADS = 12;
  private static final int TRADES_PER_THREAD = 400;
  private static final int DB_WRITES_PER_THREAD = 150;

  private SimpleDatabaseHelperV2 helper;
  private ThreadSafeEconomy economy;

  /** In-memory accounting economy with real balance checks - like Vault, but inspectable. */
  static final class ThreadSafeEconomy implements EconomyProvider {

    final Map<UUID, AtomicLong> accounts = new ConcurrentHashMap<>();
    final AtomicInteger failedOperations = new AtomicInteger();

    AtomicLong account(final QUser user) {

      return accounts.computeIfAbsent(user.getUniqueId(), k->new AtomicLong(1_000_000));
    }

    @Override
    public String name() {

      return "smoke-eco";
    }

    @Override
    public String lastError() {

      return "none";
    }

    @Override
    public boolean valid() {

      return true;
    }

    @Override
    public String format(final BigDecimal amount, final String world) {

      return amount.toPlainString();
    }

    @Override
    public BigDecimal balance(final QUser user, final String world) {

      return BigDecimal.valueOf(account(user).get());
    }

    @Override
    public boolean deposit(final QUser user, final String world, final BigDecimal amount) {

      account(user).addAndGet(amount.longValueExact());
      return true;
    }

    @Override
    public String providerName() {

      return "smoke-eco";
    }

    @Override
    public boolean withdraw(final QUser user, final String world, final BigDecimal amount) {

      final AtomicLong acc = account(user);
      final long value = amount.longValueExact();
      while(true) {
        final long current = acc.get();
        if(current < value) {
          failedOperations.incrementAndGet();
          return false;
        }
        if(acc.compareAndSet(current, current - value)) {
          return true;
        }
      }
    }
  }

  /** Minimal thread-safe inventory fake: a single counted resource with a capacity. */
  static final class FakeInventory implements InventoryWrapper {

    private long amount;
    private final long capacity;

    FakeInventory(final long initialAmount, final long capacity) {

      this.amount = initialAmount;
      this.capacity = capacity;
    }

    @Override
    public synchronized Map<Integer, ItemStack> addItem(final ItemStack... itemStacks) {

      final long add = itemStacks[0].getAmount();
      final long space = capacity - amount;
      final long accepted = Math.min(add, space);
      amount += accepted;
      if(accepted < add) {
        return Map.of(0, itemStacks[0]);
      }
      return Map.of();
    }

    @Override
    public synchronized Map<Integer, ItemStack> removeItem(final ItemStack... itemStacks) {

      final long remove = itemStacks[0].getAmount();
      final long taken = Math.min(remove, amount);
      amount -= taken;
      if(taken < remove) {
        return Map.of(0, itemStacks[0]);
      }
      return Map.of();
    }

    @Override
    public ItemStack[] createSnapshot() {

      return new ItemStack[0];
    }

    @Override
    public boolean restoreSnapshot(final ItemStack[] snapshot) {

      return true;
    }

    @Override
    public com.ghostchu.quickshop.api.inventory.InventoryWrapperIterator iterator() {

      throw new UnsupportedOperationException("not needed for the smoke loop");
    }

    @Override
    public void clear() {

    }

    @Override
    public com.ghostchu.quickshop.api.inventory.InventoryWrapperManager getWrapperManager() {

      return null;
    }

    @Override
    public org.bukkit.inventory.InventoryHolder getHolder() {

      return null;
    }

    @Override
    public com.ghostchu.quickshop.api.inventory.InventoryWrapperType getInventoryType() {

      return null;
    }

    @Override
    public org.bukkit.Location getLocation() {

      return null;
    }

    @Override
    public boolean isNeedUpdate() {

      return false;
    }

    @Override
    public void setContents(final ItemStack[] contents) {

    }
  }

  @BeforeAll
  void setUp() throws Exception {

    final QuickShop plugin = mock(QuickShop.class);
    when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    economy = new ThreadSafeEconomy();
    final EconomyManager economyManager = mock(EconomyManager.class);
    when(plugin.getEconomyManager()).thenReturn(economyManager);
    when(economyManager.provider()).thenReturn(economy);
    final YamlDocument config = mock(YamlDocument.class);
    when(plugin.getConfig()).thenReturn(config);
    when(config.getBoolean(anyString())).thenReturn(true); //skip DB version check

    //worker threads cannot see Mockito thread-local static mocks, so install real statics:
    //the Bukkit server and the QuickShop singleton are written reflectively instead
    final org.bukkit.Server server = mock(org.bukkit.Server.class);
    when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
    //Bukkit.setServer reads the Paper build manifest, so write the field directly
    final java.lang.reflect.Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, server);
    installRealStatics(plugin, server);

    final JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:qstradesmoke;MODE=MYSQL;DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    h2.setPassword("");
    final SQLManagerImpl sqlManager = new SQLManagerImpl(h2, "smoke-sql-manager");
    sqlManager.executeSQL("SET MODE=MYSQL");
    sqlManager.setExecutorPool(QuickExecutor.getHikaricpExecutor());
    helper = new SimpleDatabaseHelperV2(plugin, sqlManager, "qs_");
  }

  @AfterAll
  void tearDown() throws Exception {

    final java.lang.reflect.Field instanceField = QuickShop.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }

  private static void installRealStatics(final QuickShop plugin, final org.bukkit.Server server) throws Exception {

    final com.ghostchu.quickshop.api.QuickShopProvider provider = mock(com.ghostchu.quickshop.api.QuickShopProvider.class);
    when(provider.getApiInstance()).thenReturn(plugin);
    final org.bukkit.plugin.RegisteredServiceProvider rsp = mock(org.bukkit.plugin.RegisteredServiceProvider.class);
    when(rsp.getProvider()).thenReturn(provider);
    when(rsp.getPlugin()).thenReturn(mock(org.bukkit.plugin.Plugin.class));
    final org.bukkit.plugin.ServicesManager servicesManager = mock(org.bukkit.plugin.ServicesManager.class);
    when(servicesManager.getRegistration(com.ghostchu.quickshop.api.QuickShopProvider.class)).thenReturn(rsp);
    when(server.getServicesManager()).thenReturn(servicesManager);

    final java.lang.reflect.Field instanceField = QuickShop.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, plugin);
  }

  @Test
  void concurrentTradesStayMoneyConsistent() throws Exception {

    final QUser taxer = user("taxer");
    economy.account(taxer).set(0);
    final QUser[] owners = new QUser[THREADS];
    for(int i = 0; i < owners.length; i++) {
      owners[i] = user("owner-" + i);
      economy.account(owners[i]).set(1_000_000);
    }

    //every participant must be funded BEFORE the snapshot, or their starting balance
    //would look like money created out of thin air
    final QUser[] buyers = new QUser[THREADS];
    for(int i = 0; i < buyers.length; i++) {
      buyers[i] = user("buyer-" + i);
      economy.account(buyers[i]).set(1_000_000);
    }

    long totalBefore = 0;
    for(final Map.Entry<UUID, AtomicLong> entry : economy.accounts.entrySet()) {
      totalBefore += entry.getValue().get();
    }

    final AtomicInteger completed = new AtomicInteger();
    final AtomicInteger rolledBack = new AtomicInteger();
    final AtomicInteger dbWrites = new AtomicInteger();
    final CountDownLatch latch = new CountDownLatch(THREADS);
    final AtomicInteger errors = new AtomicInteger();

    for(int t = 0; t < THREADS; t++) {
      final QUser owner = owners[t];
      final int threadIdx = t;
      final Thread worker = new Thread(()->{
        try {
          final QUser buyer = buyers[threadIdx];
          final ItemStack item = mock(ItemStack.class);
          when(item.getAmount()).thenReturn(64);
          when(item.getType()).thenReturn(Material.DIRT);
          when(item.clone()).thenReturn(item);

          for(int i = 0; i < TRADES_PER_THREAD; i++) {
            //economy leg: buyer -> owner with 5% tax, exercised through the full commit stack
            final QSEconomyTransaction tx = QSEconomyTransaction.builder()
                    .from(buyer)
                    .to(owner)
                    .taxer(taxer)
                    .world("world")
                    .amount(BigDecimal.valueOf(100))
                    .toTax(new BigDecimal("0.05"))
                    .fromTax(BigDecimal.ZERO)
                    .build();
            if(!tx.safeCommit()) {
              rolledBack.incrementAndGet();
            } else {
              completed.incrementAndGet();
            }

            //inventory leg: chest -> buyer backpack with snapshot rollback on failure
            final FakeInventory chest = new FakeInventory(Long.MAX_VALUE / 4, Long.MAX_VALUE / 2);
            final FakeInventory backpack = new FakeInventory(0, 64);
            final SimpleInventoryTransaction invTx = SimpleInventoryTransaction.builder()
                    .from(chest)
                    .to(backpack)
                    .item(item)
                    .amount(64)
                    .build();
            //backpack holds one stack; a second transfer in the same iteration must roll back
            invTx.failSafeCommit();
            final SimpleInventoryTransaction overflowTx = SimpleInventoryTransaction.builder()
                    .from(chest)
                    .to(backpack)
                    .item(item)
                    .amount(64)
                    .build();
            overflowTx.failSafeCommit();

            //database leg: real H2 writes mixed into the loop
            if(i % (TRADES_PER_THREAD / Math.max(1, DB_WRITES_PER_THREAD)) == 0) {
              helper.updateExternalInventoryProfileCache(threadIdx + 1, 7, i).join();
              helper.saveOfflineTransactionMessage(buyer.getUniqueId(), "{\"text\":\"smoke\"}", System.currentTimeMillis()).join();
              dbWrites.incrementAndGet();
            }
          }
        } catch(final Throwable e) {
          errors.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, "smoke-worker-" + t);
      worker.start();
    }

    assertTrue(latch.await(120, java.util.concurrent.TimeUnit.SECONDS), "load loop must finish in time");
    assertEquals(0, errors.get(), "no worker may throw");
    assertEquals(THREADS * TRADES_PER_THREAD, completed.get(), "every economy transaction must commit (rollback would indicate a race)");

    long totalAfter = 0;
    for(final Map.Entry<UUID, AtomicLong> entry : economy.accounts.entrySet()) {
      totalAfter += entry.getValue().get();
    }
    assertEquals(totalBefore, totalAfter, "money must be globally conserved (buyer + owner + taxer)");

    //tax account received the full 5% of every trade: the totalTax rate-vs-amount regression
    assertEquals(completed.get() * 5L, economy.account(taxer).get(), "tax account must hold the exact collected tax");
    assertTrue(dbWrites.get() >= THREADS, "database writes must have been exercised");
  }

  /**
   * Soak-level load: 180,000 trades across 12 workers with a mixed database profile
   * (external-cache updates + offline-message inserts + reads + cutoff cleanups).
   * Asserts the same money-conservation invariants over a sustained run.
   */
  @Test
  void soakMixedLoadStaysConsistent() throws Exception {

    final int tradesPerThread = 15_000;
    final QUser taxer = user("soak-taxer");
    economy.account(taxer).set(0);
    final QUser[] owners = new QUser[THREADS];
    final QUser[] buyers = new QUser[THREADS];
    for(int i = 0; i < THREADS; i++) {
      owners[i] = user("soak-owner-" + i);
      buyers[i] = user("soak-buyer-" + i);
      economy.account(owners[i]).set(1_000_000);
      economy.account(buyers[i]).set(1_000_000);
    }

    //global conservation: accounts left over from the earlier smoke test are included on
    //both sides of the comparison, which keeps the invariant valid without filtering
    long totalBefore = 0;
    for(final Map.Entry<UUID, AtomicLong> entry : economy.accounts.entrySet()) {
      totalBefore += entry.getValue().get();
    }

    final AtomicInteger completed = new AtomicInteger();
    final AtomicInteger dbOps = new AtomicInteger();
    final CountDownLatch latch = new CountDownLatch(THREADS);
    final AtomicInteger errors = new AtomicInteger();

    for(int t = 0; t < THREADS; t++) {
      final QUser owner = owners[t];
      final QUser buyer = buyers[t];
      final int threadIdx = t;
      final Thread worker = new Thread(()->{
        try {
          final ItemStack item = mock(ItemStack.class);
          when(item.getAmount()).thenReturn(64);
          when(item.getType()).thenReturn(Material.DIRT);
          when(item.clone()).thenReturn(item);

          for(int i = 0; i < tradesPerThread; i++) {
            final QSEconomyTransaction tx = QSEconomyTransaction.builder()
                    .from(buyer)
                    .to(owner)
                    .taxer(taxer)
                    .world("world")
                    .amount(BigDecimal.valueOf(50))
                    .toTax(new BigDecimal("0.10"))
                    .fromTax(new BigDecimal("0.02"))
                    .build();
            if(!tx.safeCommit()) {
              errors.incrementAndGet();
            } else {
              completed.incrementAndGet();
            }

            final FakeInventory chest = new FakeInventory(Long.MAX_VALUE / 4, Long.MAX_VALUE / 2);
            final FakeInventory backpack = new FakeInventory(0, 64);
            SimpleInventoryTransaction.builder().from(chest).to(backpack).item(item).amount(64).build().failSafeCommit();
            SimpleInventoryTransaction.builder().from(chest).to(backpack).item(item).amount(64).build().failSafeCommit();

            //mixed database profile: writes every 2nd iteration, read+cleanup every 1000th
            if(i % 2 == 0) {
              helper.updateExternalInventoryProfileCache(100 + threadIdx, 7, i).join();
              helper.saveOfflineTransactionMessage(buyer.getUniqueId(), "{\"text\":\"soak\"}", System.currentTimeMillis()).join();
              dbOps.incrementAndGet();
            }
            if(i % 1000 == 0) {
              helper.selectPlayerMessages(buyer.getUniqueId()).join();
              helper.cleanMessageForPlayer(buyer.getUniqueId(), System.currentTimeMillis() - 60_000).join();
              dbOps.incrementAndGet();
            }
          }
        } catch(final Throwable e) {
          errors.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, "soak-worker-" + t);
      worker.start();
    }

    assertTrue(latch.await(300, java.util.concurrent.TimeUnit.SECONDS), "soak loop must finish in time");
    assertEquals(0, errors.get(), "no worker may throw or fail a commit");
    assertEquals(THREADS * tradesPerThread, completed.get());

    long totalAfter = 0;
    for(final Map.Entry<UUID, AtomicLong> entry : economy.accounts.entrySet()) {
      totalAfter += entry.getValue().get();
    }
    assertEquals(totalBefore, totalAfter, "money must stay conserved across the whole soak");
    //10% toTax + 2% fromTax of amount 50 = 6 collected per trade
    assertEquals(completed.get() * 6L, economy.account(taxer).get(), "tax account must hold the exact soak tax");
    assertTrue(dbOps.get() > 80_000, "database must have been exercised heavily, got " + dbOps.get());
  }

  private static QUser user(final String name) {

    final QUser qUser = mock(QUser.class);
    when(qUser.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
    when(qUser.getUsername()).thenReturn(name);
    return qUser;
  }
}
