package com.ghostchu.quickshop.util.economyformatter;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.util.MsgUtil;
import com.ghostchu.quickshop.util.logger.Log;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import com.ghostchu.simplereloadlib.Reloadable;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

public class EconomyFormatter implements Reloadable {

  private final QuickShop plugin;
  private boolean disableVaultFormat;
  private boolean useDecimalFormat;
  private boolean currencySymbolOnRight;
  // hot-path snapshot: every internal-format fallback (sign lines, receipts, menus)
  // consulted the config tree for the symbol; refreshed on reload
  private String currencySymbol = "$";

  public EconomyFormatter(final QuickShop plugin) {

    this.plugin = plugin;
    reloadModule();
    plugin.getReloadManager().register(this);
  }

  @Override
  public ReloadResult reloadModule() {

    this.disableVaultFormat = plugin.getConfig().getBoolean("shop.disable-vault-format", false);
    this.useDecimalFormat = plugin.getConfig().getBoolean("use-decimal-format", false);
    this.currencySymbolOnRight = plugin.getConfig().getBoolean("shop.currency-symbol-on-right", false);
    this.currencySymbol = plugin.getConfig().getString("shop.alternate-currency-symbol", "$");
    return new ReloadResult(ReloadStatus.SUCCESS, "Reload successfully.", null);
  }

  /**
   * Formats the given number according to how vault would like it. E.g. $50 or 5 dollars.
   *
   * @param n price
   *
   * @return The formatted string.
   */
  public @NotNull String format(final double n, @NotNull final World world) {

    return format(n, disableVaultFormat, world);
  }

  @NotNull
  public String format(final double n, final boolean internalFormat, @NotNull final World world) {

    if(internalFormat) {
      return getInternalFormat(n);
    }

    Log.debug("Economy Provider null check: " + (plugin.getEconomyManager().provider() == null));

    try {
      final String formatted = plugin.getEconomyManager().provider().format(BigDecimal.valueOf(n), world.getName());
      if(CommonUtil.isEmptyString(formatted)) {
        Log.debug(
                "Use alternate-currency-symbol to formatting, Cause economy plugin returned null");
        return getInternalFormat(n);
      } else {
        return formatted;
      }
    } catch(final NumberFormatException e) {
      Log.debug(e.getMessage());
      Log.debug("Use alternate-currency-symbol to formatting, Cause NumberFormatException");
      return getInternalFormat(n);
    }
  }

  private String getInternalFormat(final double amount) {

    final String formatted = useDecimalFormat? MsgUtil.decimalFormat(amount) : Double.toString(amount);
    return currencySymbolOnRight? formatted + currencySymbol : currencySymbol + formatted;
  }
}
