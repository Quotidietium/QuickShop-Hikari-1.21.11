package com.ghostchu.quickshop.command.subcommand.silent;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import com.ghostchu.quickshop.util.MsgUtil;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SubCommand_SilentUnlimited extends SubCommand_SilentBase {

  public SubCommand_SilentUnlimited(final QuickShop plugin) {

    super(plugin);
  }

  @Override
  protected void doSilentCommand(final Player sender, @NotNull final Shop shop, @NotNull final CommandParser parser) {

    // same dual gate as the visible /qs unlimited (this command is directly typeable,
    // the runtime-uuid is the only thing that was protecting it)
    if(!shop.playerAuthorize(sender.getUniqueId(), BuiltInShopPermission.SET_UNLIMITED)
       && !plugin.perm().hasPermission(sender, "quickshop.other.unlimited")) {
      plugin.text().of(sender, "no-permission").send();
      return;
    }

    shop.setUnlimited(!shop.isUnlimited());
    shop.setSignText(plugin.text().findRelativeLanguages(sender));
    MsgUtil.sendControlPanelInfo(sender, shop);

    if(shop.isUnlimited()) {
      plugin.text().of(sender, "command.toggle-unlimited.unlimited").send();
      if(plugin.getConfig().getBoolean("unlimited-shop-owner-change")) {
        final QUser uuid = ((SimpleShopManager)plugin.getShopManager()).getCacheUnlimitedShopAccount();
        plugin.getShopManager().migrateOwnerToUnlimitedShopOwner(shop);
        plugin.text().of(sender, "unlimited-shop-owner-changed", uuid).send();
      }
      return;
    }
    plugin.text().of(sender, "command.toggle-unlimited.limited").send();
    if(plugin.getConfig().getBoolean("unlimited-shop-owner-change")) {
      plugin.text().of(sender, "unlimited-shop-owner-keeped").send();
    }
  }

}
