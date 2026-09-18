package com.ghostchu.quickshop.economy;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.economy.benefit.BenefitOverflowException;
import com.ghostchu.quickshop.api.economy.benefit.BenefitProvider;
import com.ghostchu.quickshop.api.economy.benefit.BenefitsAlreadyException;
import com.ghostchu.quickshop.api.obj.QUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Pins the benefit-share invariants. The runtime {@code add()} path always enforced a
 * shares sum of at most 1 (over-paying would mint money on every trade), but the
 * database deserialize path did not: a row predating the guard or edited by hand
 * bypassed it. Such sets are now dropped wholesale (owner is paid in full) instead of
 * being honoured. Also pins the concurrent backing map: benefit commands mutate the
 * map on the main/global thread while region threads iterate it inside transaction
 * commits.
 */
class QSBenefitProviderDeserializeTest {

  private MockedStatic<QuickShop> quickShopStatic;

  @BeforeEach
  void setUp() {

    quickShopStatic = mockStatic(QuickShop.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    final com.ghostchu.quickshop.api.shop.PlayerFinder finder = mock(com.ghostchu.quickshop.api.shop.PlayerFinder.class);
    lenient().when(plugin.getPlayerFinder()).thenReturn(finder);
    lenient().when(finder.uuid2NameFuture(any(), anyBoolean(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("name"));
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
  }

  @Test
  void validSharesDeserializeIntact() {

    final String a = UUID.randomUUID().toString();
    final String b = UUID.randomUUID().toString();
    final QSBenefitProvider provider = QSBenefitProvider.deserialize("{\"" + a + "\": 0.5, \"" + b + "\": 0.5}");

    assertEquals(2, provider.benefits().size());
    assertFalse(provider.none());
    assertEquals(0.5d, provider.benefits().values().iterator().next().doubleValue(), 1e-9);
  }

  @Test
  void sharesSummingOverOneAreDroppedWholesale() {

    final String a = UUID.randomUUID().toString();
    final String b = UUID.randomUUID().toString();
    final QSBenefitProvider provider = QSBenefitProvider.deserialize("{\"" + a + "\": 0.7, \"" + b + "\": 0.6}");

    // fail-safe: the invalid set is discarded so the owner receives everything —
    // honouring it would pay beneficiaries more than was withdrawn (money minting)
    assertTrue(provider.benefits().isEmpty());
    assertTrue(provider.none());
  }

  @Test
  void emptyAndNullInputsGiveEmptyProvider() {

    assertTrue(QSBenefitProvider.deserialize(null).none());
    assertTrue(QSBenefitProvider.deserialize("").none());
  }

  @Test
  void runtimeAddStillRejectsOverflow() {

    final QSBenefitProvider provider = new QSBenefitProvider();
    final QUser first = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.randomUUID(), "first", true);

    assertDoesNotThrowAdd(provider, first, new BigDecimal("0.7"));
    assertThrows(BenefitOverflowException.class, ()->provider.add(first, new BigDecimal("0.5")));
  }

  private static void assertDoesNotThrowAdd(final BenefitProvider provider, final QUser user, final BigDecimal share) {

    try {
      provider.add(user, share);
    } catch(final BenefitOverflowException | BenefitsAlreadyException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void benefitsMapIsConcurrent() throws ReflectiveOperationException {

    final var field = QSBenefitProvider.class.getDeclaredField("benefits");
    field.setAccessible(true);
    assertInstanceOf(ConcurrentHashMap.class, field.get(new QSBenefitProvider()));
    field.setAccessible(false);
  }
}
