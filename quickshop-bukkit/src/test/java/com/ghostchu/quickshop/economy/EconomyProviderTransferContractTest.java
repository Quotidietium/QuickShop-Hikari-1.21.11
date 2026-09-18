package com.ghostchu.quickshop.economy;

import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.api.obj.QUser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pins the EconomyProvider#transfer default-method contract: a two-legged transfer
 * (withdraw from the payer, deposit to the payee). The historical implementation also
 * re-deposited into the payer — minting the amount out of thin air on every transfer.
 */
class EconomyProviderTransferContractTest {

  private EconomyProvider provider() {

    final EconomyProvider provider = mock(EconomyProvider.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    doReturn(true).when(provider).valid();
    doReturn(BigDecimal.valueOf(1_000_000)).when(provider).balance(any(), anyString());
    doReturn(true).when(provider).withdraw(any(), anyString(), any());
    doReturn(true).when(provider).deposit(any(), anyString(), any());
    return provider;
  }

  @Test
  void transferMovesMoneyWithoutMinting() {

    final EconomyProvider provider = provider();
    final QUser from = mock(QUser.class);
    final QUser to = mock(QUser.class);

    assertTrue(provider.transfer(from, to, "world", BigDecimal.valueOf(640)));

    verify(provider).withdraw(eq(from), eq("world"), eq(BigDecimal.valueOf(640)));
    verify(provider).deposit(eq(to), eq("world"), eq(BigDecimal.valueOf(640)));
    // the mint: a third leg depositing back into the payer
    verify(provider, never()).deposit(eq(from), anyString(), any());
  }

  @Test
  void failedPayeeDepositDoesNotRetryOrDoubleCharge() {

    final EconomyProvider provider = provider();
    doReturn(false).when(provider).deposit(any(), anyString(), any());

    final QUser from = mock(QUser.class);
    final QUser to = mock(QUser.class);

    assertFalse(provider.transfer(from, to, "world", BigDecimal.valueOf(640)));
    verify(provider).withdraw(eq(from), eq("world"), eq(BigDecimal.valueOf(640)));
    verify(provider).deposit(eq(to), eq("world"), eq(BigDecimal.valueOf(640)));
    verify(provider, never()).deposit(eq(from), anyString(), any());
  }

  @Test
  void invalidProviderRefusesToTransfer() {

    final EconomyProvider provider = provider();
    doReturn(false).when(provider).valid();

    assertFalse(provider.transfer(mock(QUser.class), mock(QUser.class), "world", BigDecimal.ONE));
    verify(provider, never()).withdraw(any(), anyString(), any());
    verify(provider, never()).deposit(any(), anyString(), any());
  }
}
