package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.economy.EconomyManager;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.api.economy.benefit.BenefitProvider;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.benchmark.Env;
import com.ghostchu.quickshop.economy.QSBenefitProvider;
import com.ghostchu.quickshop.economy.transaction.QSEconomyTransaction;
import com.ghostchu.quickshop.obj.QUserImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.ghostchu.quickshop.benchmark.BenchHarness.consume;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures the per-trade economy transaction overhead (tax math, event dispatch,
 * provider round trips) with a thread-safe in-memory provider.
 */
public final class EconomyBench {

  private static final int TRADER_COUNT = 64;

  private EconomyBench() {

  }

  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) {

    final QuickShop plugin = Env.plugin();
    final InMemoryEconomy provider = new InMemoryEconomy();
    final EconomyManager economyManager = Env.hotMock(EconomyManager.class);
    when(economyManager.provider()).thenReturn(provider);
    when(plugin.getEconomyManager()).thenReturn(economyManager);

    final com.ghostchu.quickshop.api.obj.QUser taxer = QUserImpl.createFullFilled(UUID.nameUUIDFromBytes("taxer".getBytes()), "taxer", true);
    final com.ghostchu.quickshop.api.obj.QUser[] owners = new com.ghostchu.quickshop.api.obj.QUser[TRADER_COUNT];
    final com.ghostchu.quickshop.api.obj.QUser[] buyers = new com.ghostchu.quickshop.api.obj.QUser[TRADER_COUNT];
    for(int i = 0; i < TRADER_COUNT; i++) {
      owners[i] = QUserImpl.createFullFilled(UUID.nameUUIDFromBytes(("owner" + i).getBytes()), "owner" + i, true);
      buyers[i] = QUserImpl.createFullFilled(UUID.nameUUIDFromBytes(("buyer" + i).getBytes()), "buyer" + i, true);
      provider.deposit(owners[i], "world", null, BigDecimal.valueOf(100_000));
      provider.deposit(buyers[i], "world", null, BigDecimal.valueOf(100_000));
    }

    harness.bench("economy/safeCommitWithTax", ctx -> {
      final int i = (int)(ctx.index++ % TRADER_COUNT);
      final QSEconomyTransaction tx = QSEconomyTransaction.builder()
              .from(buyers[i])
              .to(owners[(i + 1) % TRADER_COUNT])
              .taxer(taxer)
              .world("world")
              .amount(BigDecimal.valueOf(64))
              .toTax(new BigDecimal("0.05"))
              .fromTax(BigDecimal.ZERO)
              .build();
      consume(tx.safeCommit());
    });

    harness.bench("economy/safeCommitNoTax", ctx -> {
      final int i = (int)(ctx.index++ % TRADER_COUNT);
      final QSEconomyTransaction tx = QSEconomyTransaction.builder()
              .from(buyers[i])
              .to(owners[(i + 1) % TRADER_COUNT])
              .world("world")
              .amount(BigDecimal.valueOf(64))
              .build();
      consume(tx.safeCommit());
    });
  }

  /**
   * Thread-safe in-memory single-currency economy with the same contract as the Vault
   * provider path (BigDecimal amounts, boolean results).
   */
  private static final class InMemoryEconomy implements EconomyProvider {

    private final Map<QUser, BigDecimal> accounts = new ConcurrentHashMap<>();

    @Override
    public boolean deposit(final @NotNull QUser qUser, final @NotNull String world,
                           final @Nullable String currency, final @NotNull BigDecimal amount) {

      accounts.merge(qUser, amount, BigDecimal::add);
      return true;
    }

    @Override
    public boolean withdraw(final @NotNull QUser qUser, final @NotNull String world,
                            final @Nullable String currency, final @NotNull BigDecimal amount) {

      final BigDecimal balance = accounts.getOrDefault(qUser, BigDecimal.ZERO);
      if(balance.compareTo(amount) < 0) {
        return false;
      }
      accounts.put(qUser, balance.subtract(amount));
      return true;
    }

    @Override
    public @NotNull BigDecimal balance(final @NotNull QUser qUser, final @NotNull String world,
                                       final @Nullable String currency) {

      return accounts.getOrDefault(qUser, BigDecimal.ZERO);
    }

    @Override
    public @NotNull String name() {

      return "benchmark-eco";
    }

    @Override
    public @NotNull String providerName() {

      return "benchmark";
    }

    @Override
    public boolean valid() {

      return true;
    }

    @Override
    public boolean multiCurrency() {

      return false;
    }

    @Override
    public boolean supportsCurrency(final @NotNull String world, final @Nullable String currency) {

      return currency == null;
    }

    @Override
    public @NotNull String format(final @NotNull BigDecimal amount, final @NotNull String world,
                                  final @Nullable String currency) {

      return amount.toPlainString();
    }

    @Override
    public @Nullable String lastError() {

      return null;
    }
  }
}
