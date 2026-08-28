package com.ghostchu.quickshop.command.subcommand;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import com.ghostchu.quickshop.common.util.CommonUtil;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class SubCommand_Refill implements CommandHandler<Player> {

  private final QuickShop plugin;

  public SubCommand_Refill(final QuickShop plugin) {

    this.plugin = plugin;
  }

  @Override
  public void onCommand(@NotNull final Player sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    if(parser.getArgs().isEmpty()) {
      plugin.text().of(sender, "command.no-amount-given").send();
      return;
    }
    final int add;
    final Shop shop = getLookingShop(sender);
    if(shop == null) {
      plugin.text().of(sender, "not-looking-at-shop").send();
      return;
    }
    // add() materializes items out of thin air, so gate it to the shop's own
    // managers even for holders of the base command node.
    if(!shop.playerAuthorize(sender.getUniqueId(), BuiltInShopPermission.ACCESS_INVENTORY)
       && !plugin.perm().hasPermission(sender, "quickshop.other.refill")) {
      plugin.text().of(sender, "not-permission").send();
      return;
    }
    if(CommonUtil.isNumeric(parser.getArgs().getFirst())) {
      add = Util.parseIntegerSafely(parser.getArgs().getFirst(), -1);
      if(add < 0) {
        //digits-only but not a valid int (e.g. longer than 10 digits)
        plugin.text().of(sender, "not-a-number", parser.getArgs().getFirst()).send();
        return;
      }
    } else {
      if(parser.getArgs().getFirst().equals(plugin.getConfig().getString("shop.word-for-trade-all-items"))) {
        add = shop.getRemainingSpace();
      } else {
        plugin.text().of(sender, "not-a-number", parser.getArgs().getFirst()).send();
        return;
      }
    }
    shop.add(shop.getItem(), add);
    plugin.text().of(sender, "refill-success").send();
  }

  @NotNull
  @Override
  public List<String> onTabComplete(@NotNull final Player sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    return parser.getArgs().size() == 1? Collections.singletonList(plugin.text().of(sender, "tabcomplete.amount").plain()) : Collections.emptyList();
  }

}
