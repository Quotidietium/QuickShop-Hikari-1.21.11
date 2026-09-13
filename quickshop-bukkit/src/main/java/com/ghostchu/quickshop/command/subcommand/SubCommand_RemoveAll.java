package com.ghostchu.quickshop.command.subcommand;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.obj.QUserImpl;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logging.container.ShopRemoveLog;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.ghostchu.quickshop.util.Util.getPlayerList;

public class SubCommand_RemoveAll implements CommandHandler<CommandSender> {

  private final QuickShop plugin;

  public SubCommand_RemoveAll(final QuickShop plugin) {

    this.plugin = plugin;
  }

  @Override
  public void onCommand(@NotNull final CommandSender sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    // deleting a player's every shop is irreversible (and refunds that player for each
    // one): require an explicit confirm step like the other destructive commands
    if(parser.getArgs().isEmpty() || !"confirm".equalsIgnoreCase(parser.getArgs().getLast())) {
      plugin.text().of(sender, "command.removeall-warning").send();
      return;
    }
    final List<String> targetArgs = new ArrayList<>(parser.getArgs());
    targetArgs.remove(targetArgs.size() - 1); // strip the trailing "confirm"

    final CompletableFuture<QUser> qUserFuture;
    if(!targetArgs.isEmpty()) {
      qUserFuture = QUserImpl.createAsync(plugin.getPlayerFinder(), targetArgs.getFirst());
    } else {
      qUserFuture = QUserImpl.createAsync(plugin.getPlayerFinder(), sender);
    }
    qUserFuture
            .thenAccept(qUser->{
              final QUser executor = QUserImpl.createAsync(plugin.getPlayerFinder(), sender).join();
              if(executor.equals(qUser)) {
                if(!plugin.perm().hasPermission(sender, "quickshop.removeall.self")) {
                  plugin.text().of(sender, "no-permission").send();
                  return;
                }
              } else {
                if(!plugin.perm().hasPermission(sender, "quickshop.removeall.other")) {
                  plugin.text().of(sender, "no-permission").send();
                  return;
                }
              }
              final List<Shop> pendingRemoval = new ArrayList<>();
              for(final Shop shop : plugin.getShopManager().getAllShops()) {
                if(!shop.getOwner().equals(qUser)) {
                  continue;
                }
                pendingRemoval.add(shop);
              }
              pendingRemoval.forEach(shop->{
                plugin.logEvent(new ShopRemoveLog(qUser, "Deleting shop " + shop + " as requested by the /quickshop removeall command.", shop.saveToInfoStorage()));
                Util.regionThread(shop.bukkitLocation(), () -> plugin.getShopManager().deleteShop(shop));
              });
              plugin.text().of(sender, "command.some-shops-removed", pendingRemoval.size()).send();
            })
            .exceptionally(err->{
              plugin.text().of(sender, "internal-error", err.getMessage()).send();
              return null;
            });

  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull final CommandSender sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    final List<String> list = new ArrayList<>(getPlayerList(sender));
    list.add("confirm");
    return parser.getArgs().size() <= 1? list : Collections.emptyList();
  }
}
