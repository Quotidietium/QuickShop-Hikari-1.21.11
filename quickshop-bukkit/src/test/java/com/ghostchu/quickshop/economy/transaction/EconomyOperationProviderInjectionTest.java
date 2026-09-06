package com.ghostchu.quickshop.economy.transaction;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.economy.EconomyManager;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.api.economy.operation.EconomyDepositOperation;
import com.ghostchu.quickshop.api.economy.operation.EconomyWithdrawOperation;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.economy.QSEconomyManager;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R44 economy provider-resolution sweep: operations created by a
 * transaction carry the provider the transaction resolved at construction (the same core
 * that authorized the balance check), while the legacy three-argument constructors keep
 * the historic per-commit services-manager resolution for standalone API construction.
 */
class EconomyOperationProviderInjectionTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private EconomyManager economyManager;
  private EconomyProvider chainProvider;
  private QUser account;

  @BeforeEach
  void setUp() {

    plugin = mock(QuickShop.class);
    economyManager = mock(EconomyManager.class);
    chainProvider = mock(EconomyProvider.class);
    when(plugin.getEconomyManager()).thenReturn(economyManager);
    when(economyManager.provider()).thenReturn(chainProvider);

    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    bukkitStatic = mockStatic(Bukkit.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    when(chainProvider.name()).thenReturn("chain-eco");
    when(chainProvider.lastError()).thenReturn("none");
    when(chainProvider.valid()).thenReturn(true);
    when(chainProvider.balance(any(), anyString())).thenReturn(BigDecimal.valueOf(1_000_000));
    lenientStubs(chainProvider);

    account = mock(QUser.class);
  }

  private void lenientStubs(final EconomyProvider provider) {

    org.mockito.Mockito.lenient().when(provider.deposit(any(), anyString(), any())).thenReturn(true);
    org.mockito.Mockito.lenient().when(provider.withdraw(any(), anyString(), any())).thenReturn(true);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  @Test
  void injectedProviderCommitsAndRollsBackWithoutServicesResolution() {

    // no Bukkit services environment is needed beyond the shared harness: if commit()
    // still walked the services chain, the services lookup would not resolve the
    // injected provider and the verifies below would fail
    final EconomyProvider injected = mock(EconomyProvider.class);
    when(injected.name()).thenReturn("injected-eco");
    lenientStubs(injected);
    final BigDecimal amount = new BigDecimal("12.5");

    final EconomyWithdrawOperation withdraw = new EconomyWithdrawOperation(account, amount, "world", injected);
    assertTrue(withdraw.commit());
    verify(injected).withdraw(account, "world", amount);
    assertTrue(withdraw.rollback());
    verify(injected).deposit(account, "world", amount);
    verify(chainProvider, never()).withdraw(any(), anyString(), any());

    final EconomyDepositOperation deposit = new EconomyDepositOperation(account, amount, "world", injected);
    assertTrue(deposit.commit());
    assertTrue(deposit.rollback());
    // withdraw.commit + deposit.rollback -> 2 withdraws; withdraw.rollback + deposit.commit -> 2 deposits
    verify(injected, org.mockito.Mockito.times(2)).withdraw(account, "world", amount);
    verify(injected, org.mockito.Mockito.times(2)).deposit(account, "world", amount);
    verify(chainProvider, never()).deposit(any(), anyString(), any());
  }

  @Test
  void legacyConstructorKeepsPerCommitServicesResolution() {

    final BigDecimal amount = new BigDecimal("3");
    final EconomyWithdrawOperation withdraw = new EconomyWithdrawOperation(account, amount, "world");
    assertTrue(withdraw.commit());
    verify(chainProvider).withdraw(account, "world", amount);
  }

  @Test
  void transactionOperationsUseTheConstructionTimeProvider() {

    // the transaction resolves providerA at construction; flipping the manager to
    // providerB afterwards must not reroute the transfer — the operations must carry
    // the core that authorized the balance check
    final EconomyProvider providerA = mock(EconomyProvider.class);
    when(providerA.name()).thenReturn("core-a");
    when(providerA.lastError()).thenReturn("none");
    when(providerA.valid()).thenReturn(true);
    when(providerA.balance(any(), anyString())).thenReturn(BigDecimal.valueOf(1_000_000));
    lenientStubs(providerA);
    when(economyManager.provider()).thenReturn(providerA);

    final QUser from = mock(QUser.class);
    final QUser to = mock(QUser.class);
    final QSEconomyTransaction tx = QSEconomyTransaction.builder()
            .from(from)
            .to(to)
            .world("world")
            .amount(new BigDecimal("640"))
            .toTax(BigDecimal.ZERO)
            .fromTax(BigDecimal.ZERO)
            .build();

    final EconomyProvider providerB = mock(EconomyProvider.class);
    when(providerB.name()).thenReturn("core-b");
    lenientStubs(providerB);
    when(economyManager.provider()).thenReturn(providerB);

    assertTrue(tx.commit());
    verify(providerA).withdraw(eq(from), eq("world"), any());
    verify(providerA).deposit(eq(to), eq("world"), any());
    verify(providerB, never()).withdraw(any(), anyString(), any());
    verify(providerB, never()).deposit(any(), anyString(), any());
  }

  @Test
  void mixedCaseProviderIdsResolveIdenticallyAfterNormalization() {

    final QSEconomyManager manager = new QSEconomyManager();
    final EconomyProvider named = mock(EconomyProvider.class);
    when(named.name()).thenReturn("MyEco");
    manager.provider(named);

    manager.useProvider("myeco");
    assertSame(named, manager.provider(), "lower-case selection must resolve the upper-cased key");
    manager.useProvider("MYECO");
    assertSame(named, manager.provider(), "upper-case selection must keep resolving after re-select");
  }
}
