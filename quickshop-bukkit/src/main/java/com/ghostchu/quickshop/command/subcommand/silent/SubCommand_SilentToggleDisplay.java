package com.ghostchu.quickshop.command.subcommand.silent;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.api.event.Phase;
import com.ghostchu.quickshop.api.event.settings.type.ShopDisplayEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import com.ghostchu.quickshop.util.MsgUtil;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SubCommand_SilentToggleDisplay extends SubCommand_SilentBase {

  public SubCommand_SilentToggleDisplay(final QuickShop plugin) {

    super(plugin);
  }

  @Override
  protected void doSilentCommand(final Player sender, @NotNull final Shop shop, @NotNull final CommandParser parser) {

    if(!shop.playerAuthorize(sender.getUniqueId(), BuiltInShopPermission.TOGGLE_DISPLAY)
       && !plugin.perm().hasPermission(sender, "quickshop.other.toggledisplay")) {
      plugin.text().of(sender, "not-managed-shop").send();
      return;
    }

    ShopDisplayEvent event = new ShopDisplayEvent(Phase.PRE, shop, shop.isDisableDisplay(), !shop.isDisableDisplay());
    event.callEvent();

    event = event.clone(Phase.MAIN);
    if(event.callCancellableEvent()) {

      plugin.text().of(sender, "plugin-cancelled", event.getCancelReason());
      return;
    }

    shop.setDisableDisplay(event.updated());
    shop.setSignText(plugin.text().findRelativeLanguages(sender));
    MsgUtil.sendControlPanelInfo(sender, shop);

    event = event.clone(Phase.POST);
    event.callEvent();
  }

}
