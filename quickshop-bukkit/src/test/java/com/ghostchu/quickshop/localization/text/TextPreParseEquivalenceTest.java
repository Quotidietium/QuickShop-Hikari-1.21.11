package com.ghostchu.quickshop.localization.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Equivalence guard for the pre-parsed placeholder-hole template renderer: for every
 * real bundled message template (and adversarial synthetic ones), the new renderer must
 * produce the same serialized output as the legacy fill-and-reparse path — or decline
 * (return null) and let the legacy path run with the original inputs.
 */
class TextPreParseEquivalenceTest {

  private static final MiniMessage MM = MiniMessage.miniMessage();
  private static final TagResolver[] NO_RESOLVERS = new TagResolver[0];
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

  private static String legacy(final String raw, final Component... args) {

    return MM.serialize(MM.deserialize(MiniMessageFiller.fillRaw(raw, args), NO_RESOLVERS));
  }

  private static String modern(final String raw, final Component... args) {

    final PreParsedTemplate template = PreParsedTemplate.parse(raw, MM, NO_RESOLVERS);
    final Component rendered = template.render(args);
    return rendered == null? null : MM.serialize(rendered);
  }

  private static void assertEquivalent(final String raw, final Component... args) {

    final String expected = legacy(raw, args);
    final String actual = modern(raw, args);
    assertNotNull(expected);
    assertTrue(actual == null || Objects.equals(expected, actual),
               "template=[" + raw + "] legacy=[" + expected + "] modern=[" + actual + "]");
  }

  private static Component[] plainArgs(final int count) {

    final Component[] args = new Component[count];
    for(int i = 0; i < count; i++) {
      args[i] = Component.text("value" + i);
    }
    return args;
  }

  private static Component[] sampleArgs(final int count) {

    // shapes drawn from real call sites: numbers, legacy-colored names, plain strings
    final Component[] args = new Component[count];
    for(int i = 0; i < count; i++) {
      switch(i % 3) {
        case 0 -> args[i] = Component.text(64 + i);
        case 1 -> args[i] = LegacyComponentSerializer.legacySection().deserialize("§aOwner" + i);
        default -> args[i] = Component.text("shop-" + i);
      }
    }
    return args;
  }

  @Test
  void everyBundledTemplateRendersIdenticallyOrFallsBack() {

    final YamlConfiguration messages;
    try(final Reader reader = new InputStreamReader(
            Objects.requireNonNull(getClass().getResourceAsStream("/lang/messages.yml")), StandardCharsets.UTF_8)) {
      messages = YamlConfiguration.loadConfiguration(reader);
    } catch(final Exception e) {
      throw new IllegalStateException("bundled messages.yml missing from test classpath", e);
    }

    final List<String> templates = new ArrayList<>();
    collectTemplates(messages, "", templates);

    assertTrue(templates.size() > 200, "expected a real corpus, got " + templates.size());
    int withPlaceholders = 0;
    int directHits = 0;
    for(final String raw : templates) {
      if(raw == null || PLACEHOLDER.matcher(raw).find() == false) {
        continue;
      }
      withPlaceholders++;
      final Matcher m = PLACEHOLDER.matcher(raw);
      int max = -1;
      while(m.find()) {
        max = Math.max(max, Integer.parseInt(m.group(1)));
      }
      // plain (unstyled) arguments: the pre-parse path should cover most of these
      final Component[] plain = plainArgs(max + 1);
      if(modern(raw, plain) != null) {
        directHits++;
      }
      assertEquivalent(raw, plain);
      // mixed arguments including styled ones: must fall back, output identical
      assertEquivalent(raw, sampleArgs(max + 1));
    }
    assertTrue(withPlaceholders > 100, "placeholder corpus unexpectedly small: " + withPlaceholders);
    assertTrue(directHits > withPlaceholders / 2, "pre-parse path should cover most plain-arg templates, hit only "
            + directHits + "/" + withPlaceholders);
  }

  private void collectTemplates(final YamlConfiguration section, final String prefix, final List<String> out) {

    for(final String key : section.getKeys(false)) {
      final String path = prefix.isEmpty()? key : prefix + '.' + key;
      final String value = section.getString(path);
      if(value != null) {
        out.add(value);
      } else if(section.isConfigurationSection(path)) {
        collectTemplates(section, path, out);
      } else {
        final List<String> list = section.getStringList(path);
        out.addAll(list);
      }
    }
  }

  @Test
  void styledSurroundingInheritsOntoPlainArg() {

    assertEquivalent("<red>Price: {0}</red>", Component.text("10"));
    final String modern = modern("<red>Price: {0}</red>", Component.text("10"));
    assertNotNull(modern);
    assertEquals(legacy("<red>Price: {0}</red>", Component.text("10")), modern);
  }

  @Test
  void argFirstDoesNotLeakStyleIntoFollowingText() {

    assertEquivalent("{0} items left", Component.text(3));
    final String modern = modern("{0} items left", Component.text(3));
    assertNotNull(modern);
    assertEquals(legacy("{0} items left", Component.text(3)), modern);
  }

  @Test
  void styledArgKeepsOwnStyle() {

    assertEquivalent("Bought {0}!", LegacyComponentSerializer.legacySection().deserialize("§bDiamond"));
    assertEquivalent("A {0} B {1} C {0}", Component.text(1), Component.text(2));
  }

  @Test
  void argCarryingMiniMessageTagsMatchesLegacy() {

    assertEquivalent("Item: {0}", MM.deserialize("<gradient:#5e4fa2:#f79459>Legendary</gradient>"));
    assertEquivalent("{0}", MM.deserialize("<bold><red>x</red></bold>"));
  }

  @Test
  void placeholderInsideTagArgumentFallsBack() {

    // the sentinel stays inside the tag argument, so the guard must decline
    final PreParsedTemplate template = PreParsedTemplate.parse(
            "<hover:show_text:'tip {0}'>hover me</hover>", MM, NO_RESOLVERS);
    assertNull(template.render(Component.text("X")));
    assertEquivalent("<hover:show_text:'tip {0}'>hover me</hover>", Component.text("X"));
  }

  @Test
  void missingArgumentFallsBack() {

    final PreParsedTemplate template = PreParsedTemplate.parse("need {0} and {1}", MM, NO_RESOLVERS);
    assertNull(template.render(Component.text("only")));
    assertEquivalent("need {0} and {1}", Component.text("only"));
  }

  @Test
  void placeholderlessTemplateIsBrokenMarker() {

    final PreParsedTemplate template = PreParsedTemplate.parse("no placeholders here", MM, NO_RESOLVERS);
    assertTrue(PreParsedTemplate.isBroken(template));
    assertNull(template.render(Component.text("x")));
  }

  @Test
  void brokenSentinelTextFallsBack() {

    // a literal private-use character in the template: the walk cannot consume it as a
    // well-formed hole, so the guard must decline rather than render garbage
    final String hostile = "weird " + PreParsedTemplate.SENTINEL_START + "9text";
    final PreParsedTemplate template = PreParsedTemplate.parse(hostile, MM, NO_RESOLVERS);
    assertTrue(template.render(Component.text("x")) == null || Objects.equals(legacy(hostile, Component.text("x")), modern(hostile, Component.text("x"))));
  }
}
