package com.ghostchu.quickshop.menu.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the trade-menu quantity guard: negative chat input previously passed
 * every check (0-check, stock upper bound, positive-remainder multiple) and reached the
 * trade actions with a negative amount, flipping buy into sell.
 */
class TradeMenuQuantityTest {

  @Test
  void negativeQuantitiesAreRejected() {

    assertFalse(MainPage.isQuantityAcceptable(-1, 1, 64, false));
    assertFalse(MainPage.isQuantityAcceptable(-5, 64, 64, false));
    assertFalse(MainPage.isQuantityAcceptable(-64, 64, 64, false));
    assertFalse(MainPage.isQuantityAcceptable(Integer.MIN_VALUE, 1, 100, false));
  }

  @Test
  void zeroIsRejected() {

    assertFalse(MainPage.isQuantityAcceptable(0, 1, 64, false));
  }

  @Test
  void validPositiveMultiplesPass() {

    assertTrue(MainPage.isQuantityAcceptable(1, 1, 64, false));
    assertTrue(MainPage.isQuantityAcceptable(64, 64, 64, false));
    assertTrue(MainPage.isQuantityAcceptable(32, 16, 64, false));
  }

  @Test
  void quantitiesAboveStockAreRejected() {

    assertFalse(MainPage.isQuantityAcceptable(65, 1, 64, false));
  }

  @Test
  void unlimitedShopsIgnoreStock() {

    assertTrue(MainPage.isQuantityAcceptable(1_000_000, 1, 0, true));
  }

  @Test
  void nonMultiplesOfTheUnitSizeAreRejected() {

    assertFalse(MainPage.isQuantityAcceptable(5, 64, 64, false));
    assertFalse(MainPage.isQuantityAcceptable(63, 64, 64, false));
  }
}
