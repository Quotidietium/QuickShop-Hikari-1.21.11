package com.ghostchu.quickshop.menu.shared;
/*
 * QuickShop-Hikari
 * Copyright (C) 2026 QuickShop-Hikari Contributors
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

import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.handlers.MenuClickHandler;
import net.tnemc.menu.core.icon.Icon;
import net.tnemc.menu.core.utils.PlayerInstance;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Per-player menu page for QuickShop menus.
 *
 * <p>The stock TNML page shares one icon map across every viewer, and every icon action
 * closure captures the state of the player whose open callback built the icons. With two
 * players in the same menu the last opener's actions resolve everyone's clicks — the browse
 * teleport buttons would send a clicker to another player's shop, the trade quantity
 * buttons would trade as the last opener. {@link PlayerInstancePage} stores icons per
 * player, but in TNML 1.6.0.0-SNAPSHOT-15 (the shipped build) two defects break it out of
 * the box:</p>
 * <ul>
 *   <li>{@code addIcon(UUID, Icon)} always publishes a fresh instance carrying only the
 *       newest icon, silently discarding everything added before it;</li>
 *   <li>the click dispatch ({@code BukkitInventoryClickListener} → {@code Menu.onClick} →
 *       {@code Page.onClick(MenuClickHandler)}) resolves icons exclusively against the
 *       shared map — the per-player {@code onClick(UUID, MenuClickHandler)} overload is
 *       never invoked anywhere in the library, while {@code BukkitInventory.build} does
 *       honor per-player instances for rendering.</li>
 * </ul>
 *
 * <p>This subclass accumulates instances correctly and reroutes click resolution through
 * the clicker's own instance, so rendering and clicks finally agree on per-player icons.
 * Per-player instances must be dropped on menu close via {@link #clearInstance(UUID)} or
 * they outlive their viewers.</p>
 */
public class QuickShopPlayerPage extends PlayerInstancePage {

  public QuickShopPlayerPage(final int pageNumber) {

    super(pageNumber);
  }

  @Override
  public void addIcon(@NotNull final UUID player, @NotNull final Icon icon) {

    // upstream replaces the per-player instance on every call, keeping only the newest
    // icon — accumulate into the existing instance instead
    players.computeIfAbsent(player, key->new PlayerInstance(key)).addIcon(icon);
  }

  /**
   * The live per-player icon map, creating the instance on first use. Unlike
   * {@link #getIcons(UUID)} this never falls back to the shared map, so callers can
   * clear and rebuild it without leaking state to other viewers.
   *
   * @param player The UUID of the viewer.
   *
   * @return the viewer's own icon map
   */
  public @NotNull Map<Integer, Icon> instanceIcons(@NotNull final UUID player) {

    return players.computeIfAbsent(player, key->new PlayerInstance(key)).getIcons();
  }

  @Override
  public boolean onClick(@NotNull final MenuClickHandler handler) {

    // the library's click path only consults the shared icons map; resolve against the
    // clicker's instance (falling back to the shared map exactly like the build path)
    // so a click runs the action the clicking player sees, not the last opener's
    final UUID clicker = handler.player().identifier();
    final Map<Integer, Icon> icons = hasInstance(clicker)? getIcons(clicker) : super.getIcons();
    final int slot = handler.slot().slot();
    if(icons.containsKey(slot)) {
      if(!icons.get(slot).onClick(handler)) {
        return true;
      }
    }
    if(clickHandler != null) {
      return clickHandler.apply(handler);
    }
    // unhandled top-area clicks (empty menu slots) must be cancelled: the client can
    // plant its held item into the slot, and the menu inventory is discarded on
    // close, silently destroying the item
    return true;
  }

  /**
   * Drops the viewer's icon instance. Menus must call this from their close callback —
   * per-player instances otherwise accumulate for every player that ever opened.
   *
   * @param player The UUID of the viewer leaving the menu
   */
  public void clearInstance(@NotNull final UUID player) {

    removeInstance(player);
  }
}
