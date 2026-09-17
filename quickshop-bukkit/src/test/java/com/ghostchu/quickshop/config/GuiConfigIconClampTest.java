package com.ghostchu.quickshop.config;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the gui.yml slot/rows clamping: values out of the owning menu's bounds used to
 * reach the inventory renderer verbatim and throw IndexOutOfBoundsException on every
 * menu open (gui.yml documents "0-53" regardless of the menu's row count).
 */
class GuiConfigIconClampTest {

  private static GuiConfig.MenuConfig menu(final String yaml) throws IOException {

    final YamlDocument doc = YamlDocument.create(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
            GeneralSettings.DEFAULT, LoaderSettings.DEFAULT, DumperSettings.DEFAULT, UpdaterSettings.DEFAULT);
    return new GuiConfig.MenuConfig(doc.getSection("menu"));
  }

  @Test
  void testSlotClampedToMenuBounds() throws IOException {

    final GuiConfig.MenuConfig config = menu("""
            menu:
              rows: 4
              icon:
                material: STONE
                slot: 53
            """);
    // 4 rows = slots 0..35; 53 must clamp to 35
    assertEquals(35, config.getIcon("icon").getSlot());
  }

  @Test
  void testNegativeSlotClampedToZero() throws IOException {

    final GuiConfig.MenuConfig config = menu("""
            menu:
              rows: 4
              icon:
                slot: -3
            """);
    assertEquals(0, config.getIcon("icon").getSlot());
  }

  @Test
  void testInBoundsSlotUntouched() throws IOException {

    final GuiConfig.MenuConfig config = menu("""
            menu:
              rows: 6
              icon:
                slot: 49
            """);
    assertEquals(49, config.getIcon("icon").getSlot());
  }

  @Test
  void testBorderRowsOutOfRangeDropped() throws IOException {

    final GuiConfig.MenuConfig config = menu("""
            menu:
              rows: 3
              border:
                rows: [0, 1, 3, 7]
            """);
    // 1-based border rows: [1,3] survive; 0 and 7 are outside the 3-row menu
    final List<Integer> rows = config.getIcon("border").getRows();
    assertEquals(List.of(1, 3), rows);
  }

  @Test
  void testSlotsListClamped() throws IOException {

    final GuiConfig.MenuConfig config = menu("""
            menu:
              rows: 2
              amount:
                slots: [3, 17, 18, -1, 99]
                quantities: [1, 2, 3, 4, 5]
            """);
    // 2 rows = 0..17: 18/99 clamp to 17, -1 clamps to 0
    assertEquals(List.of(3, 17, 17, 0, 17), config.getIcon("amount").getSlots());
  }
}
