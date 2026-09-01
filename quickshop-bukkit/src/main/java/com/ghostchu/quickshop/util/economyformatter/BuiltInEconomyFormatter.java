package com.ghostchu.quickshop.util.economyformatter;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.MsgUtil;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import com.ghostchu.simplereloadlib.Reloadable;

public class BuiltInEconomyFormatter implements Reloadable {

  private final QuickShop plugin;
  private boolean useDecimalFormat;
  private boolean currencySymbolOnRight;
  // hot-path snapshot: every fallback format consulted the config tree for the symbol;
  // refreshed on reload
  private String currencySymbol = "$";

  public BuiltInEconomyFormatter(final QuickShop plugin) {

    this.plugin = plugin;
    reloadModule();
    plugin.getReloadManager().register(this);
  }

  @Override
  public ReloadResult reloadModule() {

    this.useDecimalFormat = plugin.getConfig().getBoolean("use-decimal-format", false);
    this.currencySymbolOnRight = plugin.getConfig().getBoolean("shop.currency-symbol-on-right", false);
    this.currencySymbol = plugin.getConfig().getString("shop.alternate-currency-symbol", "$");
    return new ReloadResult(ReloadStatus.SUCCESS, "Reload successfully.", null);
  }


  public String getInternalFormat(final double amount) {

    final String formatted = useDecimalFormat? MsgUtil.decimalFormat(amount) : Double.toString(amount);
    return currencySymbolOnRight? formatted + currencySymbol : currencySymbol + formatted;
  }
}
