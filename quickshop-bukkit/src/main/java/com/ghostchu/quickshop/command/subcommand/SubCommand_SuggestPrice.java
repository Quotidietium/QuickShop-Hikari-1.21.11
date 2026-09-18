package com.ghostchu.quickshop.command.subcommand;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.util.Util;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public class SubCommand_SuggestPrice implements CommandHandler<Player> {

  private final QuickShop plugin;

  public SubCommand_SuggestPrice(final QuickShop plugin) {

    this.plugin = plugin;
  }

  @Override
  public void onCommand(@NotNull final Player sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    final Shop shop = getLookingShop(sender);
    if(shop == null) {

      final ItemStack stack = sender.getInventory().getItemInMainHand();
      if(stack == null || stack.getType().equals(Material.AIR)) {

        plugin.text().of(sender, "not-looking-at-shop").send();
        return;
      }

      plugin.text().of(sender, "suggest-wait").send();
      Util.asyncThreadRun(()->{
        try {
        Shop shop1 = null;

        for(final Shop shopTest : plugin.getShopManager().getAllShops()) {
          if(plugin.getItemMatcher().matches(stack, shopTest.getItem())) {
            shop1 = shopTest;
            break;
          }
        }

        if(shop1 == null) {
          plugin.text().of(sender, "cannot-suggest-price", 0).send();
          return;
        }

        final List<Double> matchedBuy = plugin.getShopManager().getAllShops().stream()
                .filter(s->s.isBuying())
                .filter(s->plugin.getItemMatcher().matches(stack, s.getItem()))
                .map(Shop::getPrice)
                .toList();

        final List<Double> matchedSell = plugin.getShopManager().getAllShops().stream()
                .filter(s->s.isSelling())
                .filter(s->plugin.getItemMatcher().matches(stack, s.getItem()))
                .map(Shop::getPrice)
                .toList();

        final int totalSize = (matchedBuy.size() + matchedSell.size());

        if(totalSize < 3) {
          plugin.text().of(sender, "cannot-suggest-price", totalSize).send();
          return;
        }

        // stats per side only when that side has enough samples: the totalSize gate
        // above can pass with one side empty, and med()/avg() on an empty list throw
        // (get(-1)) or return NaN
        if(matchedBuy.size() >= 3) {
          final double min = CommonUtil.min(matchedBuy);
          final double max = CommonUtil.max(matchedBuy);
          final double avg = CommonUtil.avg(matchedBuy);
          final double med = CommonUtil.med(matchedBuy);
          final Component suggest = plugin.text().of(sender, "price-suggest", matchedBuy.size(), format(max, shop1), format(min, shop1), format(avg, shop1), format(med, shop1), format(med, shop1)).forLocale();
          plugin.text().of(sender, "price-suggest-multi", plugin.text().of(sender, "shop-type.buying").forLocale(), suggest).send();
        }

        if(matchedSell.size() >= 3) {
          final double min = CommonUtil.min(matchedSell);
          final double max = CommonUtil.max(matchedSell);
          final double avg = CommonUtil.avg(matchedSell);
          final double med = CommonUtil.med(matchedSell);
          final Component suggest = plugin.text().of(sender, "price-suggest", matchedSell.size(), format(max, shop1), format(min, shop1), format(avg, shop1), format(med, shop1), format(med, shop1)).forLocale();
          plugin.text().of(sender, "price-suggest-multi", plugin.text().of(sender, "shop-type.selling").forLocale(), suggest).send();
        }
        } catch(final Exception e) {
          // the async task has no caller left to receive the throwable — without this
          // the player waits forever behind "suggest-wait" while the error only hits logs
          plugin.logger().warn("Failed to suggest price for {}", sender.getName(), e);
          plugin.text().of(sender, "internal-error").send();
        }
      });
      return;
    }
    plugin.text().of(sender, "suggest-wait").send();
    Util.asyncThreadRun(()->{
      try {
      final List<Double> matched = plugin.getShopManager().getAllShops().stream()
              .filter(s->s.getShopId() != shop.getShopId())
              .filter(s->s.shopType().identifier().equalsIgnoreCase(shop.shopType().identifier()))
              .filter(s->plugin.getItemMatcher().matches(shop.getItem(), s.getItem()))
              .map(Shop::getPrice)
              .toList();
      if(matched.size() < 3) {
        plugin.text().of(sender, "cannot-suggest-price", matched.size()).send();
        return;
      }
      final double min = CommonUtil.min(matched);
      final double max = CommonUtil.max(matched);
      final double avg = CommonUtil.avg(matched);
      final double med = CommonUtil.med(matched);
      plugin.text().of(sender, "price-suggest", matched.size(), format(max, shop), format(min, shop), format(avg, shop), format(med, shop), format(med, shop)).send();
      } catch(final Exception e) {
        plugin.logger().warn("Failed to suggest price for {}", sender.getName(), e);
        plugin.text().of(sender, "internal-error").send();
      }
    });
  }

  private String format(final double d, final Shop shop) {

    return plugin.getShopManager().format(d, shop);
  }
}
