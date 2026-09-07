package com.ghostchu.quickshop.localization.text;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.MsgUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * R45 render-pipeline contracts: the compiled pre-parsed template (skeleton frozen at
 * parse, argument validation + assembly at render) and the brace-scan fast path of
 * {@link MsgUtil#fillArgs(Component, Component...)}.
 */
class TextRenderPipelineR45Test {

  private static final MiniMessage MM = MiniMessage.miniMessage();
  private static final TagResolver[] NO_RESOLVERS = new TagResolver[0];

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;

  @BeforeEach
  void setUp() {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder()).thenReturn(new File("."));
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  /** The exact historical fill-and-reparse pipeline, as the pre-parsed path must match it. */
  private static String legacy(final String raw, final Component... args) {

    return MM.serialize(MM.deserialize(MiniMessageFiller.fillRaw(raw, args), NO_RESOLVERS));
  }

  private static void assertMatchesLegacy(final String raw, final Component... args) {

    final PreParsedTemplate template = PreParsedTemplate.parse(raw, MM, NO_RESOLVERS);
    final Component rendered = template.render(args);
    assertEquals(legacy(raw, args), MM.serialize(rendered), "template=[" + raw + "]");
  }

  // ---- compiled template ----

  @Test
  void compiledSkeletonRendersRepeatedlyWithDifferentArguments() {

    final PreParsedTemplate template = PreParsedTemplate.parse("Buy {0} for {1}$", MM, NO_RESOLVERS);
    assertFalse(PreParsedTemplate.isBroken(template));
    assertMatchesLegacy("Buy {0} for {1}$", Component.text("apple"), Component.text("12"));
    assertMatchesLegacy("Buy {0} for {1}$", Component.text("diamond"), Component.text("99"));
    assertEquals(MM.serialize(template.render(Component.text("apple"), Component.text("12"))),
                 MM.serialize(template.render(Component.text("apple"), Component.text("12"))));
    assertNotEquals(MM.serialize(template.render(Component.text("apple"), Component.text("12"))),
                    MM.serialize(template.render(Component.text("diamond"), Component.text("99"))));
  }

  @Test
  void sentinelCarryingArgumentFallsBack() {

    final PreParsedTemplate template = PreParsedTemplate.parse("Item: {0}", MM, NO_RESOLVERS);
    assertFalse(PreParsedTemplate.isBroken(template));
    final Component hostile = Component.text("weird " + PreParsedTemplate.SENTINEL_START);
    assertNull(template.render(hostile));
  }

  @Test
  void argumentShortOfHighestIndexFallsBack() {

    final PreParsedTemplate template = PreParsedTemplate.parse("{0} and {2}", MM, NO_RESOLVERS);
    assertFalse(PreParsedTemplate.isBroken(template), "maxIndex is derivable at compile");
    assertNull(template.render(Component.text("a"), Component.text("b")));
    assertMatchesLegacy("{0} and {2}", Component.text("a"), Component.text("b"), Component.text("c"));
  }

  @Test
  void nullArgumentSlotFallsBack() {

    final PreParsedTemplate template = PreParsedTemplate.parse("a {0} b", MM, NO_RESOLVERS);
    assertNull(template.render(new Component[]{null}));
  }

  @Test
  void holeNestedUnderSplitNodeChildrenIsBrokenAtParse() {

    // MiniMessage nests styled runs as children of the preceding text node, so {1}
    // rides below {0}'s node; the split cannot consume it and every historical render
    // fell back — the compiled form must record that as permanently broken
    final String raw = "<gray>(AdminOnly) <light_purple>{0}<dark_gray> denied, try adding <light_purple>{1} <gray>now.";
    final PreParsedTemplate template = PreParsedTemplate.parse(raw, MM, NO_RESOLVERS);
    assertTrue(PreParsedTemplate.isBroken(template), "leftover sentinel must be frozen at parse");
    assertNull(template.render(Component.text("v0"), Component.text("v1")));
  }

  @Test
  void placeholderInsideTagArgumentIsBrokenAtParse() {

    final PreParsedTemplate template = PreParsedTemplate.parse(
            "<hover:show_text:'tip {0}'>hover me</hover>", MM, NO_RESOLVERS);
    assertTrue(PreParsedTemplate.isBroken(template), "the sentinel-in-tag case must be frozen at parse");
  }

  // ---- fillArgs brace-scan fast path ----

  /** The exact historical body: sequential literal passes plus the trailing compact. */
  private static String slowPathReference(final Component origin, final Component... args) {

    Component current = origin;
    for(int i = 0; i < args.length; i++) {
      current = current.replaceText(TextReplacementConfig.builder()
                                            .matchLiteral("{" + i + "}")
                                            .replacement(args[i] == null? Component.empty() : args[i])
                                            .build());
    }
    return MM.serialize(current.compact());
  }

  @Test
  void braceFreeTreeMatchesHistoricalSlowPath() {

    final Component origin = MM.deserialize("<red>Price:</red> 12.34 <gray>each</gray>");
    final String actual = MM.serialize(MsgUtil.fillArgs(origin, Component.text("ignored")));
    assertEquals(slowPathReference(origin, Component.text("ignored")), actual);
  }

  @Test
  void nestedPlaceholderInsideArgumentIsStillRefilled() {

    // an argument value carrying "{1}" makes the scan find a brace; the sequential
    // passes must then re-fill it exactly like before
    final Component origin = Component.text().content("A {0} B").append(Component.text("tail")).build();
    final String actual = MM.serialize(MsgUtil.fillArgs(origin, Component.text("x{1}y"), Component.text("Z")));
    assertEquals(slowPathReference(origin, Component.text("x{1}y"), Component.text("Z")), actual);
    assertTrue(actual.contains("Z"), "the nested {1} inside the argument must have been refilled");
  }

  @Test
  void braceInsideHoverValueIsDetectedAndFilled() {

    final Component origin = Component.text("hover me").hoverEvent(HoverEvent.showText(Component.text("tip {0}")));
    final String actual = MM.serialize(MsgUtil.fillArgs(origin, Component.text("HIT")));
    assertTrue(actual.contains("HIT"), "hover values participate in the scan domain");
    assertEquals(slowPathReference(origin, Component.text("HIT")), actual);
  }

  @Test
  void braceInsideTranslatableComponentArgumentIsDetectedAndFilled() {

    final Component translatable = Component.translatable("my.key", Component.text("arg {0}"));
    final String actual = MM.serialize(MsgUtil.fillArgs(translatable, Component.text("T")));
    assertTrue(actual.contains("T"), "component translation arguments participate in the scan domain");
    assertEquals(slowPathReference(translatable, Component.text("T")), actual);
  }

  @Test
  void argumentBracesWithoutTreeBracesNeverSplice() {

    // the scan covers the tree only; an argument that is never inserted cannot
    // introduce a match — identical to the historical no-match passes
    final Component origin = Component.text("plain 12.34");
    final String actual = MM.serialize(MsgUtil.fillArgs(origin, Component.text("{9}")));
    assertEquals(slowPathReference(origin, Component.text("{9}")), actual);
    assertFalse(actual.contains("{9}"));
  }

  @Test
  void emptyContentContainerCompactsIdentically() {

    // regression shape from the pre-parsed path: container nodes with empty content
    // compact identically on both paths
    final Component origin = Component.text("", net.kyori.adventure.text.format.Style.style(
            net.kyori.adventure.text.format.NamedTextColor.RED)).append(Component.text("inner"));
    final String actual = MM.serialize(MsgUtil.fillArgs(origin, Component.text("x")));
    assertEquals(slowPathReference(origin, Component.text("x")), actual);
    assertTrue(actual.contains("inner"));
  }
}
