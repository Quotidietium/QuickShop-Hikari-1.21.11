package com.ghostchu.quickshop.addon.plan;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.ExtensionService;
import org.bukkit.Bukkit;

public class PlanHook {

  private final Main main;
  private final CapabilityService capabilities = CapabilityService.getInstance();

  public PlanHook(final Main main) {

    this.main = main;
  }

  public void hookIntoPlan() {

    registerDataExtension();
    listenForPlanReloads();
  }

  private void registerDataExtension() {

    try {
      if(capabilities.hasCapability("DATA_EXTENSION_TABLES")
         && capabilities.hasCapability("DATA_EXTENSION_VALUES")) {
        final HikariDataExtension extension = new HikariDataExtension(main);
        ExtensionService.getInstance().register(extension);
        // kept so onDisable can unregister it — Plan would keep polling the extension
        // (and its SQL against a closed pool) after this plugin disabled
        main.setDataExtension(extension);
      } else {
        main.getLogger().severe("Your Plan build doesn't support DATA_EXTENSION_TABLES or DATA_EXTENSION_VALUES capability!");
        Bukkit.getPluginManager().disablePlugin(main);
      }
    } catch(final IllegalStateException planIsNotEnabled) {
      main.getLogger().warning("Plan is not enabled; the QuickShop data extension was not registered.");
    } catch(final IllegalArgumentException dataExtensionImplementationIsInvalid) {
      main.getLogger().warning("The QuickShop data extension implementation was rejected by Plan: " + dataExtensionImplementationIsInvalid.getMessage());
    }
  }

  private void listenForPlanReloads() {

    CapabilityService.getInstance().registerEnableListener(
            isPlanEnabled->{
              // Register DataExtension again
              if(isPlanEnabled) {
                registerDataExtension();
              }
            }
                                                          );
  }
}
