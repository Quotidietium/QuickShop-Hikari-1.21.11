package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.common.util.CalculateUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for the money-math precision fix: the historical DECIMAL32 rounding
 * (7 significant digits) silently distorted trade totals and tax splits once amounts
 * crossed ~1e7 — a 1234567.89 x 64 total charged 79012340 instead of 79012344.96, and
 * the rounding direction decided whether the buyer overpaid or the owner was shorted.
 */
class CalculateUtilExactMathTest {

  @Test
  void multiplyIsExactBeyondSevenSignificantDigits() {

    final double total = CalculateUtil.multiply(1234567.89d, 64d);
    assertEquals(79_012_344.96d, total, 1e-6, "price x amount must not round to 7 significant digits");
  }

  @Test
  void smallMoneyMathIsUnchanged() {

    assertEquals(6.4d, CalculateUtil.multiply(0.1d, 64d), 1e-9);
    assertEquals(0.3d, CalculateUtil.add(0.1d, 0.2d), 1e-9, "0.1 + 0.2 must stay the decimal 0.3, not the binary drift");
    assertEquals(0.95d, CalculateUtil.subtract(BigDecimal.ONE, new BigDecimal("0.05")).doubleValue(), 1e-9);
  }

  @Test
  void taxSplitConservesMoneyExactly() {

    // the QSEconomyTransaction construction: afterTax = (1 - rate) * amount,
    // toTax = amount - afterTax — the two must always sum back to the amount
    final BigDecimal amount = new BigDecimal("12345678.99");
    final BigDecimal rate = BigDecimal.valueOf(0.05d);
    final BigDecimal afterTax = CalculateUtil.multiply(CalculateUtil.subtract(BigDecimal.ONE, rate), amount);
    final BigDecimal tax = CalculateUtil.subtract(amount, afterTax);
    assertEquals(0, amount.compareTo(afterTax.add(tax)), "afterTax + tax must equal the amount exactly");
    assertEquals(new BigDecimal("11728395.0405"), afterTax, "5% off 12345678.99 must be exact to the cent digit");
  }

  @Test
  void doubleRateConversionCarriesNoBinaryNoise() {

    // BigDecimal.valueOf uses the shortest decimal form; new BigDecimal(0.05) would
    // carry the full binary expansion (0.05000000000000000277...) into the tax math
    assertEquals(new BigDecimal("0.05"), BigDecimal.valueOf(0.05d));
    assertEquals(0, new BigDecimal("0.05").compareTo(BigDecimal.valueOf(0.05d)));
  }
}
