package com.ghostchu.quickshop.economy;
/*
 * QuickShop-Hikari
 * Copyright (C) 2025 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.ghostchu.quickshop.BuiltInSolution;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.economy.provider.VaultProvider;
import com.ghostchu.quickshop.util.performance.PerfMonitor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Objects;
import java.util.UUID;

/**
 * EconomyLoader
 *
 * @author creatorfromhell
 * @since 6.2.0.11
 */
public class EconomyLoader {

  private final QuickShop plugin;

  public EconomyLoader(final QuickShop plugin) {

    this.plugin = plugin;
  }

  public boolean load() {

    try(final PerfMonitor ignored = new PerfMonitor("Loading Economy Bridge")) {
      return setup();
    } catch(final Exception e) {

      plugin.logger().error("Something went wrong while trying to load the economy system!");
      plugin.logger().error("QuickShop was unable to hook into an economy system (Couldn't find Vault)!");
      plugin.logger().error("QuickShop can NOT enable properly!");
      plugin.setupBootError(BuiltInSolution.econError(), false);
      plugin.logger().error("Plugin Listeners have been disabled. Please fix this economy issue.", e);
      return false;
    }
  }

  public boolean setup() throws Exception {

    final EconomyProvider providerInstance = loadVault();
    if(providerInstance == null || !providerInstance.valid()) {
      plugin.setupBootError(BuiltInSolution.econError(), false);
      return false;
    }

    plugin.getEconomyManager().provider(providerInstance);
    plugin.getEconomyManager().useProvider(providerInstance.name());
    plugin.logger().info("Selected economy bridge: {}", providerInstance.name());
    return true;
  }

  private EconomyProvider loadVault() {

    final VaultProvider vault = new VaultProvider(plugin);
    final boolean taxEnabled = plugin.getConfig().getDouble("tax", 0.0d) > 0;
    final String taxAccount = plugin.getConfig().getString("tax-account", "tax");
    if(!vault.valid()) {
      return null;
    }
    if(!taxEnabled) {
      return vault;
    }
    if(CommonUtil.isEmptyString(taxAccount)) {
      return vault;
    }
    final OfflinePlayer tax;
    if(CommonUtil.isUUID(taxAccount)) {
      tax = Bukkit.getOfflinePlayer(UUID.fromString(taxAccount));
    } else {
      tax = Bukkit.getOfflinePlayer(taxAccount);
    }
    if(!Objects.requireNonNull(vault.economy()).hasAccount(tax)) {
      plugin.logger().warn("QuickShop detected that no tax account exists and will try to create one. If you see any errors, please change the tax-account name in the config.yml to that of the Server owner.");
      if(vault.economy().createPlayerAccount(tax)) {
        plugin.logger().info("Tax account created.");
      } else {
        plugin.logger().warn("Cannot create tax-account, please change the tax-account name in the config.yml to that of the server owner");
      }
      if(!vault.economy().hasAccount(tax)) {
        plugin.logger().warn("Player for the Tax-account has never played on this server before and we couldn't create an account. This may cause server lag or economy errors, therefore changing the name is recommended. You may ignore this warning if it doesn't cause any issues.");
      }
    }
    return vault;
  }
}
