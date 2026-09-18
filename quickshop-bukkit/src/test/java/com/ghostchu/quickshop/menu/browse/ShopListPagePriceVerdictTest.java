package com.ghostchu.quickshop.menu.browse;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the price-verdict classification of the shop list page. The verdict maps each
 * price/average ratio to a language-keyed lore line that carries its own color; the
 * retired implementation derived the color afterwards by calling
 * {@code String.contains("Great"/"Below"/...)} on the English indicator text, which any
 * translation would have broken. Thresholds must stay exactly as they were.
 */
class ShopListPagePriceVerdictTest {

  private final Object verdict(final double price, final double avg, final boolean selling) throws Exception {

    final ShopListPage page = new ShopListPage("browse", 6);
    final Method method = ShopListPage.class.getDeclaredMethod("priceVerdict", double.class, double.class, boolean.class);
    method.setAccessible(true);
    try {
      return method.invoke(page, price, avg, selling);
    } finally {
      method.setAccessible(false);
    }
  }

  private static String keyOf(final Object verdict) throws Exception {

    return (String)verdict.getClass().getDeclaredMethod("lineKey").invoke(verdict);
  }

  @Test
  void sellingShopThresholds() throws Exception {

    assertEquals("gui.browse.price-line.great-deal", keyOf(verdict(84, 100, true)));
    assertEquals("gui.browse.price-line.below-avg", keyOf(verdict(94, 100, true)));
    assertEquals("gui.browse.price-line.expensive", keyOf(verdict(116, 100, true)));
    assertEquals("gui.browse.price-line.above-avg", keyOf(verdict(106, 100, true)));
    assertEquals("gui.browse.price-line.average", keyOf(verdict(100, 100, true)));
  }

  @Test
  void buyingShopThresholds() throws Exception {

    assertEquals("gui.browse.price-line.great-price", keyOf(verdict(116, 100, false)));
    assertEquals("gui.browse.price-line.above-avg", keyOf(verdict(106, 100, false)));
    assertEquals("gui.browse.price-line.low-offer", keyOf(verdict(84, 100, false)));
    assertEquals("gui.browse.price-line.below-avg", keyOf(verdict(94, 100, false)));
    assertEquals("gui.browse.price-line.average", keyOf(verdict(100, 100, false)));
  }

  @Test
  void zeroAverageFallsBackToPlainLine() throws Exception {

    assertEquals("gui.browse.price-line.plain", keyOf(verdict(10, 0, true)));
    assertEquals("gui.browse.price-line.plain", keyOf(verdict(10, 0, false)));
  }
}
