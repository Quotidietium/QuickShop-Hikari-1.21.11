package com.ghostchu.quickshop.addon.limited.command;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.limited.Main;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.api.event.CalendarEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class SubCommand_Limit implements CommandHandler<Player> {

  private final QuickShop quickshop;

  public SubCommand_Limit(final QuickShop quickshop) {

    this.quickshop = quickshop;
  }

  @Override
  public void onCommand(final Player sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    if(parser.getArgs().isEmpty()) {
      quickshop.text().of(sender, "command.wrong-args").send();
      return;
    }
    final Shop shop = getLookingShop(sender);
    if(shop == null) {
      quickshop.text().of(sender, "not-looking-at-shop").send();
      return;
    }
    if(!shop.playerAuthorize(sender.getUniqueId(), BuiltInShopPermission.OWNERSHIP_TRANSFER)) {
      quickshop.text().of(sender, "not-managed-shop").send();
      return;
    }
    switch(parser.getArgs().getFirst()) {
      case "set" -> {
        if(parser.getArgs().size() < 2) {
          quickshop.text().of(sender, "command.wrong-args").send();
          return;
        }
        final int limitAmount;
        try {
          limitAmount = Integer.parseInt(parser.getArgs().get(1));
        } catch(NumberFormatException e) {
          quickshop.text().of(sender, "not-a-integer", parser.getArgs().get(1)).send();
          return;
        }
        mutateOnShopRegion(sender, shop, manager->{
          if(limitAmount > 0) {
            manager.set("limit", limitAmount);
            quickshop.text().of(sender, "addon.limited.success-setup").send();
          } else {
            manager.set("limit", null);
            manager.set("data", null);
            quickshop.text().of(sender, "addon.limited.success-remove").send();
          }
        });
      }
      case "unset" -> mutateOnShopRegion(sender, shop, manager->{
        manager.set("limit", null);
        manager.set("data", null);
        quickshop.text().of(sender, "addon.limited.success-remove").send();
      });
      case "reset" -> mutateOnShopRegion(sender, shop, manager->{
        manager.set("data", null);
        quickshop.text().of(sender, "addon.limited.success-reset").send();
      });
      case "period" -> {
        if(parser.getArgs().size() < 2) {
          quickshop.text().of(sender, "command.wrong-args").send();
          return;
        }
        try {
          final CalendarEvent.CalendarTriggerType type = CalendarEvent.CalendarTriggerType.valueOf(parser.getArgs().get(1).toUpperCase(Locale.ROOT));
          mutateOnShopRegion(sender, shop, manager->{
            manager.set("period", type.name());
            quickshop.text().of(sender, "addon.limited.success-setup").send();
          });
        } catch(IllegalArgumentException ignored) {
          quickshop.text().of(sender, "command.wrong-args", parser.getArgs().get(1)).send();
        }
      }
    }
  }

  /**
   * Runs a shop-extra mutation on the shop's own region thread: the command executes on
   * the player's region, while purchases mutate the same YamlConfiguration from the shop's
   * region (Folia) — unguarded setExtra here raced them.
   */
  private void mutateOnShopRegion(final Player sender, final Shop shop, final java.util.function.Consumer<ConfigurationSection> mutator) {

    com.ghostchu.quickshop.util.Util.regionThread(shop.bukkitLocation(), ()->{
      final ConfigurationSection manager = shop.getExtra(Main.instance);
      mutator.accept(manager);
      shop.setExtra(Main.instance, manager);
    });
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull final Player sender, @NotNull final String commandLabel, @NotNull final String[] cmdArg) {

    if(cmdArg.length < 2) {
      return List.of("set", "unset", "reset", "period");
    }
    if(cmdArg.length < 3) {
      switch(cmdArg[0]) {
        case "set" -> {
          return List.of("<max>");
        }
        case "period" -> {
          return Arrays.stream(CalendarEvent.CalendarTriggerType.values())
                  .filter(e->!e.equals(CalendarEvent.CalendarTriggerType.SECOND))
                  .filter(e->!e.equals(CalendarEvent.CalendarTriggerType.NOTHING_CHANGED))
                  .map(Enum::name).toList();
        }
      }
    }
    return Collections.emptyList();
  }
}
