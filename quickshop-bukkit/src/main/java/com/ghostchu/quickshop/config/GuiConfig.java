package com.ghostchu.quickshop.config;
/*
 * QuickShop-Hikari
 * Copyright (C) 2024 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.config.QSConfig;
import com.ghostchu.quickshop.util.logger.Log;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GuiConfig - Manages GUI configuration loading and access
 *
 * @author creatorfromhell
 * @since 6.2.0.11
 */
public class GuiConfig extends QSConfig {

  private final QuickShop plugin;
  private final Map<String, MenuConfig> menuConfigs = new HashMap<>();

  public GuiConfig(@NotNull final QuickShop plugin) {

    super("gui.yml", "gui.yml", Collections.emptyList(),
          LoaderSettings.builder().setAutoUpdate(true).build(),
          UpdaterSettings.builder().setAutoSave(true)
                  .setVersioning(new BasicVersioning("version")).build());

    this.plugin = plugin;

    loadConfig();
    plugin.getReloadManager().register(this);
  }

  public void loadConfig() {

    Log.debug("GUI Config Loading.");
    menuConfigs.clear();

    this.load();
    if(this.yaml == null) {
      // a syntactically broken gui.yml makes QSConfig.load() give up and leave yaml
      // null; pressing on used to NPE at getSection with an error pointing nowhere
      // near the real cause. Fall back to the embedded defaults, loudly.
      plugin.logger().error("Failed to parse gui.yml - falling back to the bundled defaults. Fix the file and run /quickshop reload.");
      try(final java.io.InputStream defaults = getResource("gui.yml")) {
        if(defaults == null) {
          throw new IllegalStateException("Bundled gui.yml resource is missing from the jar; cannot start the menu system.");
        }
        this.yaml = YamlDocument.create(defaults, settings);
      } catch(final java.io.IOException ex) {
        throw new IllegalStateException("Cannot load gui.yml nor its bundled defaults", ex);
      }
    }

    // Load menu configurations
    loadMenuConfig("trade");
    loadMenuConfig("keeper");
    loadMenuConfig("staff");
    loadMenuConfig("history");
    loadMenuConfig("browse");

    Log.debug("GUI Config Loaded. Menus: " + menuConfigs.keySet());
  }

  private void loadMenuConfig(final String menuName) {

    final Section section = this.yaml.getSection(menuName);
    if(section != null) {
      final MenuConfig config = new MenuConfig(section);
      config.validateSlots(menuName);
      menuConfigs.put(menuName, config);
    }
  }

  @Nullable
  public MenuConfig getMenuConfig(final String menuName) {

    return menuConfigs.get(menuName);
  }

  @Override
  public ReloadResult reloadModule() throws Exception {

    loadConfig();
    plugin.logger().info("GUI configuration reloaded successfully. Menus loaded: " + menuConfigs.keySet());
    plugin.logger().info("Note: Changes to menu row counts require a server restart to take effect.");
    return ReloadResult.builder().status(ReloadStatus.SUCCESS).build();
  }

  /**
   * Represents configuration for a single menu
   */
  public static class MenuConfig {

    private final Section section;
    private final Map<String, IconConfig> icons = new HashMap<>();

    public MenuConfig(final Section section) {

      this.section = section;
      loadIcons();
    }

    private void loadIcons() {

      final int maxSlot = getRows() * 9 - 1;
      for(final Object keyObj : section.getKeys()) {

        final String key = String.valueOf(keyObj);
        if(key.equals("title") || key.equals("rows")) continue;

        final Object value = section.get(key);
        if(value instanceof final Section iconSection) {
          icons.put(key, new IconConfig(iconSection, maxSlot));
        }
      }
    }

    /**
     * Surface slot misconfiguration once at load time. Out-of-range values are also
     * clamped by the icon accessors at render time - an unclamped slot used to reach
     * the inventory renderer and throw IndexOutOfBoundsException on every menu open.
     * Intentional same-slot pairs (rendered conditionally by the pages) are not
     * reported.
     */
    public void validateSlots(final String menuName) {

      for(final Map.Entry<String, IconConfig> entry : icons.entrySet()) {
        final Section iconSection = entry.getValue().section();
        if(iconSection == null || !iconSection.contains("slot")) {
          continue;
        }
        final int raw = iconSection.getInt("slot", 0);
        if(raw < 0 || raw > entry.getValue().maxSlot()) {
          QuickShop.getInstance().logger().warn("gui.yml: icon '" + menuName + "." + entry.getKey()
                                                        + "' has slot " + raw + " outside the menu (0-" + entry.getValue().maxSlot() + "); it will be clamped.");
        }
      }
    }

    public int getRows() {

      return section.getInt("rows", 6);
    }

    @Nullable
    public IconConfig getIcon(final String iconName) {

      return icons.get(iconName);
    }

    @NotNull
    public Section getSection() {

      return section;
    }
  }

  /**
   * Represents configuration for a single icon/button
   */
  public record IconConfig(Section section, int maxSlot) {

    public IconConfig(final Section section) {

      this(section, 53);
    }

    @NotNull
    public String getMaterial() {

      return section.getString("material", "STONE");
    }

    @Nullable
    public String getName() {

      return section.getString("name");
    }

    @NotNull
    public List<String> getLore() {

      final Object loreObj = section.get("lore");
      if(loreObj instanceof List<?>) {
        final List<String> result = new ArrayList<>();
        for(final Object item : (List<?>)loreObj) {
          if(item != null) {
            result.add(item.toString());
          }
        }
        return result;
      } else if(loreObj instanceof String) {
        final List<String> result = new ArrayList<>();
        result.add((String)loreObj);
        return result;
      }
      return new ArrayList<>();
    }

    /** Clamped into the owning menu's bounds - see MenuConfig#validateSlots. */
    public int getSlot() {

      return Math.min(Math.max(section.getInt("slot", 0), 0), maxSlot);
    }

    @NotNull
    public List<Integer> getSlots() {

      final List<Integer> raw = section.getIntList("slots");
      final List<Integer> result = new ArrayList<>(raw.size());
      for(final Integer slot : raw) {
        result.add(Math.min(Math.max(slot, 0), maxSlot));
      }
      return result;
    }

    @NotNull
    public List<Integer> getQuantities() {

      return section.getIntList("quantities");
    }

    /** Border rows are 1-based; values outside the menu are dropped. */
    @NotNull
    public List<Integer> getRows() {

      final int maxRow = (maxSlot + 1) / 9;
      final List<Integer> raw = section.getIntList("rows");
      final List<Integer> result = new ArrayList<>(raw.size());
      for(final Integer row : raw) {
        if(row >= 1 && row <= maxRow) {
          result.add(row);
        }
      }
      return result;
    }

    @Override
    @Nullable
    public Section section() {

      return section;
    }

    @Nullable
    public IconConfig getSubIcon(final String name) {

      final Section sub = section.getSection(name);
      return sub != null? new IconConfig(sub, maxSlot) : null;
    }
  }
}
