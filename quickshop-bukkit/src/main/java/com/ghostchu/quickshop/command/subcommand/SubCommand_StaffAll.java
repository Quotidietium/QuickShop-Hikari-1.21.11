package com.ghostchu.quickshop.command.subcommand;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermissionGroup;
import com.ghostchu.quickshop.util.MsgUtil;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logger.Log;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SubCommand_StaffAll implements CommandHandler<Player> {

  private final QuickShop plugin;
  private final List<String> tabCompleteList = List.of("add", "del", "list", "clear");

  public SubCommand_StaffAll(final QuickShop plugin) {

    this.plugin = plugin;
  }

  @Override
  public void onCommand(@NotNull final Player sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    final List<Shop> shops = plugin.getShopManager().getAllShops(sender.getUniqueId());
    if(!shops.isEmpty()) {
      switch(parser.getArgs().size()) {
        case 1 -> {
          switch(parser.getArgs().getFirst()) {
            case "clear" -> {
              for(final Shop shop : shops) {
                shop.playersCanAuthorize(BuiltInShopPermissionGroup.STAFF).forEach(staff->shop.setPlayerGroup(staff, BuiltInShopPermissionGroup.EVERYONE));
              }
              plugin.text().of(sender, "shop-staff-cleared").send();
              return;
            }
            case "list" -> {
              for(final Shop shop : shops) {
                final List<UUID> staffs = shop.playersCanAuthorize(BuiltInShopPermissionGroup.STAFF);
                if(staffs.isEmpty()) {
                  MsgUtil.sendDirectMessage(sender, plugin.text().of(sender, "tableformat.left_begin").forLocale()
                          .append(plugin.text().of(sender, "shop-staff-empty").forLocale()));
                  // continue, not return: the first staff-less shop must not cut the
                  // listing of the player's remaining shops
                  continue;
                }
                Util.asyncThreadRun(()->{
                  for(final UUID uuid : staffs) {
                    MsgUtil.sendDirectMessage(sender, plugin.text().of(sender, "tableformat.left_begin").forLocale()
                            .append(Component.text(Optional.ofNullable(plugin.getPlayerFinder().uuid2Name(uuid)).orElse("Unknown")).color(NamedTextColor.GRAY)));
                  }
                });
              }

              return;
            }
            default -> {
              plugin.text().of(sender, "command.wrong-args").send();
              return;
            }
          }
        }
        case 2 -> {
          final String name = parser.getArgs().get(1);
          // same ghost-player gate as /qs staff: name resolution never fails, so a typo
          // would otherwise add a phantom staff UUID to EVERY owned shop at once
          if(org.bukkit.Bukkit.getPlayerExact(name) == null && org.bukkit.Bukkit.getOfflinePlayerIfCached(name) == null) {
            plugin.text().of(sender, "unknown-player").send();
            return;
          }
          plugin.getPlayerFinder().name2UuidFuture(parser.getArgs().get(1))
                  .thenAccept(uuid->{
                    BuiltInShopPermissionGroup permissionGroup = null;
                    switch(parser.getArgs().getFirst()) {
                      case "add" -> {
                        permissionGroup = BuiltInShopPermissionGroup.STAFF;
                        plugin.text().of(sender, "shop-staff-added", name).send();
                      }
                      case "del" -> {
                        permissionGroup = BuiltInShopPermissionGroup.EVERYONE;
                        plugin.text().of(sender, "shop-staff-deleted", name).send();
                      }
                      default -> plugin.text().of(sender, "command.wrong-args").send();
                    }
                    if(permissionGroup != null) {
                      final BuiltInShopPermissionGroup group = permissionGroup;
                      // setPlayerGroup mutates shop state + schedules a save: keep it
                      // on the main thread like every other shop mutation
                      Util.mainThreadRun(()->{
                        for(final Shop shop : shops) {
                          shop.setPlayerGroup(uuid, group);
                        }
                      });
                    }
                  })
                  .exceptionally(throwable->{
                    Log.debug("Failed set the user group: " + throwable.getMessage());
                    plugin.text().of(sender, "internal-error", throwable.getMessage()).send();
                    return null;
                  });
          return;
        }
        default -> {
          plugin.text().of(sender, "command.wrong-args").send();
          return;
        }
      }
    }
    plugin.text().of(sender, "not-looking-at-shop").send();
  }

  @NotNull
  @Override
  public List<String> onTabComplete(
          @NotNull final Player sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    if(parser.getArgs().size() == 1) {
      return tabCompleteList;
    } else if(parser.getArgs().size() == 2) {
      final String prefix = parser.getArgs().getFirst().toLowerCase();
      if("add".equals(prefix) || "del".equals(parser.getArgs().getFirst())) {
        return Util.getPlayerList(sender);
      }
    }
    return Collections.emptyList();
  }
}
