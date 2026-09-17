package com.ghostchu.quickshop.command.subcommand;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import com.ghostchu.quickshop.menu.browse.BrowseFilterMode;
import com.ghostchu.quickshop.menu.browse.BrowseSortMode;
import com.ghostchu.quickshop.util.Util;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.core.manager.MenuManager;
import net.tnemc.menu.core.viewer.MenuViewer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.ghostchu.quickshop.menu.ShopBrowseMenu.BROWSE_FILTER;
import static com.ghostchu.quickshop.menu.ShopBrowseMenu.BROWSE_SEARCH;
import static com.ghostchu.quickshop.menu.ShopBrowseMenu.BROWSE_SORT;
import static com.ghostchu.quickshop.menu.ShopBrowseMenu.BROWSE_WORLD_ONLY;
import static com.ghostchu.quickshop.menu.ShopBrowseMenu.SHOPS_DATA;


public class SubCommand_Browse implements CommandHandler<Player> {

  private final QuickShop plugin;

  public SubCommand_Browse(final QuickShop plugin) {

    this.plugin = plugin;
  }

  @Override
  public void onCommand(@NotNull final Player sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    final MenuViewer viewer = new MenuViewer(sender.getUniqueId());
    // a stale viewer would keep its old data map (addViewer only merges scalars) — start clean
    MenuManager.instance().removeViewer(sender.getUniqueId());
    MenuManager.instance().addViewer(viewer);

    final MenuPlayer menuPlayer = QuickShop.getInstance().createMenuPlayer(sender);

    final boolean world = (!parser.getArgs().isEmpty() && parser.getArgs().getFirst().equalsIgnoreCase("world"));

    // Initialize browse state
    viewer.addData(BROWSE_SORT, BrowseSortMode.PRICE_ASC);
    viewer.addData(BROWSE_FILTER, BrowseFilterMode.ALL);
    viewer.addData(BROWSE_SEARCH, "");
    viewer.addData(BROWSE_WORLD_ONLY, world);

    Util.asyncThreadRun(()->{
      final List<Shop> shops = new ArrayList<>();
      // same privacy model as /qs find: shops that revoked the viewer's SEARCH grant
      // (or whose owner blocked them via the group model) must not leak here — the browse
      // menu renders locations and teleport actions for every listed shop
      final boolean bypass = plugin.perm().hasPermission(sender, "quickshop.other.search");

      if(world) {
        shops.addAll(plugin.getShopManager().getAllShops().stream().filter(shop->{
          if(shop.bukkitLocation().getWorld() == null
             || sender.getLocation().getWorld() == null) {
            return false;
          }

          return shop.bukkitLocation().getWorld().getUID().equals(sender.getLocation().getWorld().getUID());
        }).filter(shop->bypass || shop.playerAuthorize(sender.getUniqueId(), BuiltInShopPermission.SEARCH)).toList());
      } else {
        shops.addAll(plugin.getShopManager().getAllShops().stream()
                             .filter(shop->bypass || shop.playerAuthorize(sender.getUniqueId(), BuiltInShopPermission.SEARCH)).toList());
      }

      viewer.addData(SHOPS_DATA, shops);
      // warm the inventory-count snapshot here (async) so the first main-thread render
      // reads an already-filled map instead of waiting on the cold-load budget
      com.ghostchu.quickshop.menu.browse.MarketUtils.loadInventoryCaches(shops);
      // the menu open builds the per-player icon map and reads the player's world —
      // it must not race main-thread click resolution on the page maps, and world
      // access is region-sensitive under Folia
      Util.mainThreadRun(()->MenuManager.instance().open("qs:browse", 1, menuPlayer));
    });
  }

  @Override
  public @Nullable List<String> onTabComplete(@NotNull final Player sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    if(parser.getArgs().size() == 1) {
      return List.of("world", plugin.text().of(sender, "browse-command-leave-blank").plain());
    }
    return Collections.emptyList();
  }
}
