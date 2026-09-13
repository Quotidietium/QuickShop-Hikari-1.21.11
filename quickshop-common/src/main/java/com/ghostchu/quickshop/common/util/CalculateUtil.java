package com.ghostchu.quickshop.common.util;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * CalculateUtil used for calculate between doubles
 *
 * @author sandtechnology
 */
public final class CalculateUtil {

  /**
   * Only used where the result can be non-terminating (division). Addition, subtraction
   * and multiplication of the decimal conversions used here always terminate, so they run
   * exact: the historical DECIMAL32 rounding (7 significant digits) silently distorted
   * totals and tax splits once amounts crossed ~1e7, charging players a rounded total
   * that differed from price × amount.
   */
  private static final MathContext DIVISION_CONTEXT = MathContext.DECIMAL64;

  private CalculateUtil() {

  }

  public static double add(final double number1, final double number2) {

    return (BigDecimal.valueOf(number1).add(BigDecimal.valueOf(number2))).doubleValue();
  }

  public static BigDecimal add(final BigDecimal number1, final BigDecimal number2) {

    return number1.add(number2);
  }

  public static double divide(final double number1, final double number2) {

    return (BigDecimal.valueOf(number1).divide(BigDecimal.valueOf(number2), DIVISION_CONTEXT)).doubleValue();
  }

  public static BigDecimal divide(final BigDecimal number1, final BigDecimal number2) {

    return number1.divide(number2, DIVISION_CONTEXT);
  }

  public static double multiply(final double number1, final double number2) {

    return (BigDecimal.valueOf(number1).multiply(BigDecimal.valueOf(number2))).doubleValue();
  }

  public static BigDecimal multiply(final BigDecimal number1, final BigDecimal number2) {

    return number1.multiply(number2);
  }

  public static double subtract(final double number1, final double number2) {

    return (BigDecimal.valueOf(number1).subtract(BigDecimal.valueOf(number2))).doubleValue();
  }

  public static BigDecimal subtract(final BigDecimal number1, final BigDecimal number2) {

    return number1.subtract(number2);
  }

}
