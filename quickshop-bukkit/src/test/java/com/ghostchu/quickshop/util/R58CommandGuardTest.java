package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.common.util.CommonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins two command-surface guards:
 *
 * <ul>
 *   <li>the item-lookup name pattern must reject the empty string — {@code /qs lookup
 *       create} with a stray double space used to pass the {@code *} quantifier and
 *       then blow up inside Bukkit's MemorySection with an empty path;</li>
 *   <li>{@code CommonUtil.med} on an empty list throws — this is why /qs suggestprice
 *       may only compute side statistics when that side has at least three samples
 *       (the total-size gate alone can pass with one side empty).</li>
 * </ul>
 */
class R58CommandGuardTest {

  @Test
  void itemMarkerPatternRejectsEmptyAndKeepsCharset() {

    final Pattern pattern = Pattern.compile(ItemMarker.getNameRegExp());

    assertFalse(pattern.matcher("").matches(), "empty names must be rejected (Bukkit set() would throw)");
    assertTrue(pattern.matcher("a").matches());
    assertTrue(pattern.matcher("A_b-0".replace('-', '9')).matches());
    assertTrue(pattern.matcher("Item_42").matches());
    assertFalse(pattern.matcher("bad name").matches());
    assertFalse(pattern.matcher("bad-name").matches());
  }

  @Test
  void medianOfEmptyListThrows() {

    assertThrows(IndexOutOfBoundsException.class, ()->CommonUtil.med(List.of()));
  }
}
