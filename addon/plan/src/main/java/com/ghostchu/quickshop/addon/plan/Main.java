package com.ghostchu.quickshop.addon.plan;

import com.djrapitops.plan.extension.ExtensionService;
import com.ghostchu.quickshop.QuickShop;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin implements Listener {

  private QuickShop quickshop;
  private HikariDataExtension dataExtension;

  @Override
  public void onEnable() {

    saveDefaultConfig();
    quickshop = QuickShop.getInstance();
    new PlanHook(this).hookIntoPlan();
  }

  @Override
  public void onDisable() {

    // without the unregister, Plan keeps polling the extension (and its queries against
    // a shutting-down database) long after this plugin disabled
    if(dataExtension != null) {
      try {
        ExtensionService.getInstance().unregister(dataExtension);
      } catch(final IllegalStateException | IllegalArgumentException e) {
        getLogger().warning("Failed to unregister the Plan data extension: " + e.getMessage());
      }
      dataExtension = null;
    }
  }

  public QuickShop getQuickShop() {

    return quickshop;
  }

  public void setDataExtension(final HikariDataExtension dataExtension) {

    this.dataExtension = dataExtension;
  }

  public HikariDataExtension getDataExtension() {

    return dataExtension;
  }
}
