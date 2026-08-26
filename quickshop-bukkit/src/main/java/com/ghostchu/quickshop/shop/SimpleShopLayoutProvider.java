package com.ghostchu.quickshop.shop;


/*
 * QuickShop-Hikari
 * Copyright (C) 2025 Daniel "creatorfromhell" Vidmar
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
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.shop.IShopLayoutProvider;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.type.BuyingType;
import com.ghostchu.quickshop.api.shop.type.SellingType;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import com.ghostchu.simplereloadlib.Reloadable;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SimpleShopLayoutProvider
 *
 * @author creatorfromhell
 * @since 6.2.0.11
 */
public class SimpleShopLayoutProvider implements IShopLayoutProvider, Reloadable {

  private final String[] defaultLayout = new String[] {
          "header", "trading", "item", "price"
  };

  private final QuickShop plugin;

  // layout templates per shop-type identifier, read from config once per type and
  // cleared on reload; the per-render path lookups were four config tree walks each
  private final Map<String, String[]> layoutCache = new ConcurrentHashMap<>();

  // hot-path snapshot of the renderItem flag (was a config lookup per render);
  // refreshed on reload
  private boolean forceUseItemOriginalName;

  public SimpleShopLayoutProvider(final QuickShop plugin) {

    this.plugin = plugin;
    plugin.getReloadManager().register(this);
    init();
  }

  private void init() {

    layoutCache.clear();
    forceUseItemOriginalName = plugin.getConfig().getBoolean("shop.force-use-item-original-name");
  }

  @Override
  public ReloadResult reloadModule() {

    init();
    return ReloadResult.builder().status(ReloadStatus.SUCCESS).build();
  }

  /**
   * Generates a layout template for the specified shop.
   *
   * @param shop the shop instance for which the layout template is to be generated
   *
   * @return a LinkedList of Strings representing the layout template of the shop
   */
  @Override
  public LinkedList<String> layoutTemplate(final Shop shop) {

    final String[] cached = layoutCache.computeIfAbsent(shop.shopType().identifier(), id->{
      final String[] template = new String[4];
      final String baseNode = "shop.layout." + id + ".line";
      for(int i = 0; i < 4; i++) {
        template[i] = QuickShop.getInstance().getConfig().getString(baseNode + (i + 1), defaultLayout[i]);
      }
      return template;
    });
    final LinkedList<String> template = new LinkedList<>();
    for(final String line : cached) {
      template.add(line);
    }
    return template;
  }

  /**
   * Renders the layout of the specified shop into a list of components based on the provided locale.
   *
   * @param shop the shop instance for which the components are to be rendered
   * @param locale the locale to be used for rendering the components
   *
   * @return a LinkedList of components representing the visual layout of the shop
   */
  @Override
  public LinkedList<Component> render(@NotNull final Shop shop, @NotNull final ProxiedLocale locale) {

    final LinkedList<String> template = layoutTemplate(shop);

    final LinkedList<Component> renderedLines = new LinkedList<>();

    // For the built-in shop types, the header's availability check and the trading
    // line's remaining count consult the SAME quantity (selling: getRemainingStock,
    // buying: getRemainingSpace — both equal shopType().remainingStock(shop)), so one
    // inventory scan serves both lines instead of two identical scans per refresh.
    // Third-party types keep the independent calls: their remainingStock() may differ
    // from what Shop#inventoryAvailable() consults, and exactness outranks the saving.
    final boolean shareableScan = shop.shopType() instanceof SellingType || shop.shopType() instanceof BuyingType;
    Integer remaining = null;

    for(int i = 0; i < 4; i++) {

      if(template.size() <= i || template.get(i).isBlank()) {
        renderedLines.add(Component.empty());
        continue;
      }

      switch(template.get(i).toLowerCase(Locale.ROOT)) {
        case "header":
          if(shareableScan) {
            if(remaining == null) {
              remaining = shop.shopType().remainingStock(shop);
            }
            // mirrors Shop#inventoryAvailable()'s branch order for built-in types:
            // unlimited shops are always available, otherwise the type's quantity
            // decides (neither built-in type reaches the frozen fall-through, since
            // isSelling() == !isBuying() covers both)
            renderedLines.add(renderHeader(shop, locale, shop.isUnlimited() || remaining > 0));
          } else {
            renderedLines.add(renderHeader(shop, locale));
          }
          break;
        case "trading":
          if(shareableScan) {
            if(remaining == null) {
              remaining = shop.shopType().remainingStock(shop);
            }
            renderedLines.add(renderTrading(shop, locale, remaining));
          } else {
            renderedLines.add(renderTrading(shop, locale));
          }
          break;
        case "item":
          renderedLines.add(renderItem(shop, locale));
          break;
        case "price":
          renderedLines.add(renderPrice(shop, locale));
          break;
      }
    }

    return renderedLines;
  }

  /**
   * Renders the header section of the specified shop into a component.
   *
   * @param shop   the shop instance for which the header component is to be rendered
   * @param locale the locale to be used for rendering the header component
   *
   * @return a component representing the visual header of the shop
   */
  @Override
  public Component renderHeader(final @NotNull Shop shop, final @NotNull ProxiedLocale locale) {

    return renderHeader(shop, locale, shop.inventoryAvailable());
  }

  /**
   * Renders the header section with a precomputed availability, letting a caller that
   * already holds the shop's remaining quantity skip the second inventory scan that
   * {@link Shop#inventoryAvailable()} would perform.
   *
   * @param shop      the shop instance for which the header component is to be rendered
   * @param locale    the locale to be used for rendering the header component
   * @param available the precomputed availability, as {@link Shop#inventoryAvailable()} would return
   *
   * @return a component representing the visual header of the shop
   */
  public Component renderHeader(final @NotNull Shop shop, final @NotNull ProxiedLocale locale, final boolean available) {

    final String headerKey = available? "signs.header-available" : "signs.header-unavailable";

    return plugin.text().of(headerKey, shop.ownerName(false, locale)).forLocale(locale.getLocale());
  }

  /**
   * Renders the trading section of the specified shop into a component.
   *
   * @param shop   the shop instance for which the trading component is to be rendered
   * @param locale the locale to be used for rendering the trading component
   *
   * @return a component representing the trading section of the shop
   */
  @Override
  public Component renderTrading(final @NotNull Shop shop, final @NotNull ProxiedLocale locale) {

    return renderTrading(shop, locale, shop.shopType().remainingStock(shop));
  }

  /**
   * Renders the trading section with a precomputed remaining count, letting a caller
   * that already scanned the inventory skip the rescan {@code remainingStock} would perform.
   *
   * @param shop           the shop instance for which the trading component is to be rendered
   * @param locale         the locale to be used for rendering the trading component
   * @param shopRemaining  the precomputed remaining count from {@code shop.shopType().remainingStock(shop)}
   *
   * @return a component representing the trading section of the shop
   */
  public Component renderTrading(final @NotNull Shop shop, final @NotNull ProxiedLocale locale, final int shopRemaining) {

    final String tradingStringKey = (shop.isStackingShop()? shop.shopType().stackTradingTranslationKey() : shop.shopType().tradingTranslationKey());
    final String noRemainingStringKey = shop.shopType().outOfStockTranslationKey();

    final String finalTradingStringKey = (shop.shopState().overrideShopTypeText())? shop.shopState().translationKey() : tradingStringKey;

    final Component trading = switch(shopRemaining) {
      //Unlimited
      case -1 -> plugin.text().of(finalTradingStringKey, plugin.text().of("signs.unlimited").forLocale(locale.getLocale())).forLocale(locale.getLocale());
      //No remaining
      case 0 -> {
        if(shop.shopState().overrideShopTypeText()) {
          yield plugin.text().of(shop.shopState().translationKey()).forLocale(locale.getLocale());
        }
        yield plugin.text().of(noRemainingStringKey).forLocale(locale.getLocale());
      }
      //Has remaining
      default -> plugin.text().of(finalTradingStringKey, Component.text(shopRemaining)).forLocale(locale.getLocale());
    };
    return trading;
  }

  /**
   * Renders an item section of the specified shop into a component based on the provided locale.
   *
   * @param shop the shop for which the item component is to be rendered
   * @param locale the locale to be used for rendering the item component
   *
   * @return a component representing the item section of the shop
   */
  @Override
  public Component renderItem(final @NotNull Shop shop, final @NotNull ProxiedLocale locale) {

    if(forceUseItemOriginalName
       || !shop.getItem().hasItemMeta() || !shop.getItem().getItemMeta().hasDisplayName()) {

      final Component left = plugin.text().of("signs.item-left").forLocale(locale.getLocale());
      final Component right = plugin.text().of("signs.item-right").forLocale(locale.getLocale());
      final Component itemName = Util.getItemStackName(shop.getItem());

      return left.append(itemName).append(right);
    }

    return plugin.text().of("signs.item-left").forLocale(locale.getLocale())
            .append(Util.getItemStackName(shop.getItem())
                            .append(plugin.text().of("signs.item-right").forLocale(locale.getLocale())));
  }

  /**
   * Renders the price section of the specified shop into a component.
   *
   * @param shop the shop for which the price component is to be rendered
   * @param locale the locale to be used for rendering the price component
   *
   * @return a component representing the price section of the shop
   */
  @Override
  public Component renderPrice(final @NotNull Shop shop, final @NotNull ProxiedLocale locale) {

    if(shop.isStackingShop()) {

      return plugin.text().of("signs.stack-price",
                               plugin.getShopManager().format(shop.getPrice(), shop),
                               shop.getItem().getAmount(),
                               Util.getItemStackName(shop.getItem())).forLocale(locale.getLocale());
    }

    return plugin.text().of("signs.price", plugin.getShopManager().format(shop.getPrice(), shop)).forLocale(locale.getLocale());
  }
}
