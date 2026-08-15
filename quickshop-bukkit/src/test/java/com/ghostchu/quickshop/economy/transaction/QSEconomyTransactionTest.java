package com.ghostchu.quickshop.economy.transaction;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.economy.EconomyManager;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.api.economy.benefit.BenefitProvider;
import com.ghostchu.quickshop.api.economy.transaction.EconomyTransaction;
import com.ghostchu.quickshop.api.economy.transaction.TransactionCallback;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.economy.QSBenefitProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the money-path fixes in QSEconomyTransaction:
 * tax deposit guard, onFailed callbacks and benefit-share hardening.
 */
class QSEconomyTransactionTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private EconomyProvider provider;
  private QUser from;
  private QUser to;
  private QUser taxer;

  @BeforeEach
  void setUp() {

    final QuickShop plugin = mock(QuickShop.class);
    final EconomyManager economyManager = mock(EconomyManager.class);
    provider = mock(EconomyProvider.class);
    when(plugin.getEconomyManager()).thenReturn(economyManager);
    when(economyManager.provider()).thenReturn(provider);

    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);

    bukkitStatic = mockStatic(Bukkit.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    when(provider.name()).thenReturn("test-eco");
    when(provider.lastError()).thenReturn("none");
    when(provider.valid()).thenReturn(true);
    when(provider.balance(any(), anyString(), any())).thenReturn(BigDecimal.valueOf(1_000_000));
    when(provider.deposit(any(), anyString(), any(), any())).thenReturn(true);
    when(provider.withdraw(any(), anyString(), any(), any())).thenReturn(true);

    from = mock(QUser.class);
    to = mock(QUser.class);
    taxer = mock(QUser.class);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private QSEconomyTransactionBuilder baseBuilder(final String amount, final String toTax, final String fromTax) {

    return QSEconomyTransaction.builder()
            .from(from)
            .to(to)
            .taxer(taxer)
            .world("world")
            .amount(new BigDecimal(amount))
            .toTax(toTax == null? null : new BigDecimal(toTax))
            .fromTax(fromTax == null? null : new BigDecimal(fromTax));
  }

  @Test
  void taxIsDepositedToTaxAccount() {

    //640 with 5% toTax: buyer pays 640, owner gets 608, tax account gets 32
    final QSEconomyTransaction tx = baseBuilder("640", "0.05", "0").build();

    assertTrue(tx.commit());
    verify(provider).withdraw(eq(from), eq("world"), any(), amountEq("640"));
    verify(provider).deposit(eq(to), eq("world"), any(), amountEq("608.00"));
    verify(provider).deposit(eq(taxer), eq("world"), any(), amountEq("32.00"));
  }

  @Test
  void moneyIsConservedAcrossWithdrawAndDeposits() {

    final QSEconomyTransaction tx = baseBuilder("640", "0.05", "0").build();
    assertTrue(tx.commit());

    final ArgumentCaptor<BigDecimal> withdrawn = ArgumentCaptor.forClass(BigDecimal.class);
    final ArgumentCaptor<BigDecimal> deposited = ArgumentCaptor.forClass(BigDecimal.class);
    verify(provider, times(1)).withdraw(eq(from), anyString(), any(), withdrawn.capture());
    verify(provider, times(2)).deposit(any(), anyString(), any(), deposited.capture());

    final BigDecimal totalDeposited = deposited.getAllValues().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, withdrawn.getValue().compareTo(totalDeposited), "deposits must equal the withdrawal");
  }

  @Test
  void zeroTaxNeverDepositsToTaxAccount() {

    final QSEconomyTransaction tx = baseBuilder("640", "0", "0").build();

    assertTrue(tx.commit());
    verify(provider, never()).deposit(eq(taxer), anyString(), any(), any());
    verify(provider).deposit(eq(to), eq("world"), any(), amountEq("640"));
  }

  @Test
  void invalidBenefitSharesAreSkippedAndOwnerKeepsRemainder() {

    final QUser goodUser = mock(QUser.class);
    final QUser badUser = mock(QUser.class);
    final Map<QUser, BigDecimal> benefits = new HashMap<>();
    benefits.put(goodUser, new BigDecimal("0.3"));
    benefits.put(badUser, new BigDecimal("-0.1"));

    final QSEconomyTransaction tx = baseBuilder("640", "0", "0")
            .benefitManager(new QSBenefitProvider(benefits))
            .build();

    assertTrue(tx.commit());
    //the valid share is paid, the corrupted share is skipped, the owner keeps the remainder
    verify(provider).deposit(eq(goodUser), anyString(), any(), amountEq("192.0"));
    verify(provider, never()).deposit(eq(badUser), anyString(), any(), any());
    verify(provider).deposit(eq(to), anyString(), any(), amountEq("448.0"));
  }

  @Test
  void nullRecipientWithBenefitsActsAsMoneySink() {

    final QUser goodUser = mock(QUser.class);
    final Map<QUser, BigDecimal> benefits = new HashMap<>();
    benefits.put(goodUser, new BigDecimal("0.3"));

    final QSEconomyTransaction tx = baseBuilder("100", "0", "0")
            .to(null)
            .benefitManager(new QSBenefitProvider(benefits))
            .build();

    assertTrue(tx.commit());
    verify(provider).withdraw(eq(from), anyString(), any(), amountEq("100"));
    verify(provider).deposit(eq(goodUser), anyString(), any(), amountEq("30.0"));
    verify(provider, times(1)).deposit(any(), anyString(), any(), any());
  }

  @Test
  void pluginCancelNotifiesOnFailed() {

    final boolean[] failed = {false};
    final TransactionCallback callback = new TransactionCallback() {
      @Override
      public boolean onCommit(final EconomyTransaction transaction) {

        return false;
      }

      @Override
      public void onFailed(final EconomyTransaction transaction) {

        failed[0] = true;
      }
    };

    final QSEconomyTransaction tx = baseBuilder("640", "0", "0").build();
    assertFalse(tx.commit(callback));
    assertTrue(failed[0], "onFailed must be invoked when a plugin cancels the commit");
    verify(provider, never()).withdraw(any(), anyString(), any(), any());
  }

  @Test
  void withdrawFailureNotifiesOnFailedAndDepositsNothing() {

    when(provider.withdraw(any(), anyString(), any(), any())).thenReturn(false);
    final boolean[] failed = {false};
    final TransactionCallback callback = new TransactionCallback() {
      @Override
      public void onFailed(final EconomyTransaction transaction) {

        failed[0] = true;
      }
    };

    final QSEconomyTransaction tx = baseBuilder("640", "0", "0").build();
    assertFalse(tx.commit(callback));
    assertTrue(failed[0], "onFailed must be invoked when the withdrawal fails");
    verify(provider, never()).deposit(any(), anyString(), any(), any());
  }

  @Test
  void safeCommitRollsBackDepositsWhenWithdrawalFails() {

    //withdraw succeeds, then the owner deposit fails; safeCommit must compensate the withdrawal
    when(provider.withdraw(any(), anyString(), any(), any())).thenReturn(true);
    when(provider.deposit(eq(to), anyString(), any(), any())).thenReturn(false);

    final QSEconomyTransaction tx = baseBuilder("640", "0", "0").build();
    assertFalse(tx.safeCommit());

    final ArgumentCaptor<BigDecimal> reDeposited = ArgumentCaptor.forClass(BigDecimal.class);
    //rollback compensates the withdrawal with a deposit back to the buyer
    verify(provider, times(1)).deposit(eq(from), anyString(), any(), reDeposited.capture());
    assertEquals(0, new BigDecimal("640").compareTo(reDeposited.getValue()));
  }

  private static BigDecimal amountEq(final String expected) {

    return org.mockito.ArgumentMatchers.argThat(value->value != null && value.compareTo(new BigDecimal(expected)) == 0);
  }
}
