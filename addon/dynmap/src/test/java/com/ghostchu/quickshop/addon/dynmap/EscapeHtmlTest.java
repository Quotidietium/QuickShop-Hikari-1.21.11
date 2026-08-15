package com.ghostchu.quickshop.addon.dynmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression test for the dynmap marker XSS: dynmap renders marker labels and
 * descriptions as HTML, and shop/item names are arbitrary player-controlled text.
 * Every HTML-significant character must be escaped before the value reaches a marker.
 */
class EscapeHtmlTest {

  @Test
  void scriptTagsAreNeutralized() {

    assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", Main.escapeHtml("<script>alert(1)</script>"));
  }

  @Test
  void imgOnloadInjectionIsNeutralized() {

    final String escaped = Main.escapeHtml("<img src=x onerror=alert(1)>");
    assertFalse(escaped.contains("<img"), "no raw tag may survive escaping");
    assertEquals("&lt;img src=x onerror=alert(1)&gt;", escaped);
  }

  @Test
  void attributeBreakoutIsNeutralized() {

    //quotes would break out of HTML attributes if markers are embedded in one
    final String escaped = Main.escapeHtml("\" onmouseover=\"alert(1)");
    assertFalse(escaped.contains("\""), "raw double quotes must not survive");
    assertEquals("&quot; onmouseover=&quot;alert(1)", escaped);
  }

  @Test
  void ampersandIsEscapedFirstToPreventDoubleEncoding() {

    assertEquals("&amp;lt;script&amp;gt;", Main.escapeHtml("&lt;script&gt;"));
  }

  @Test
  void apostrophesAreEscaped() {

    assertFalse(Main.escapeHtml("it's").contains("'"));
    assertEquals("it&#39;s", Main.escapeHtml("it's"));
  }

  @Test
  void plainNamesPassThroughUnchanged() {

    assertEquals("Ghost_chu's Diamond Shop", Main.escapeHtml("Ghost_chu's Diamond Shop").replace("&#39;", "'"));
    assertEquals("DIAMOND x64", Main.escapeHtml("DIAMOND x64"));
  }

  @Test
  void nullInputYieldsEmptyString() {

    assertEquals("", Main.escapeHtml(null));
  }
}
