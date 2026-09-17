package com.ghostchu.quickshop.addon.list;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.list.command.SubCommand_List;
import com.ghostchu.quickshop.api.command.CommandContainer;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin implements Listener {

  static Main instance;
  private QuickShop plugin;
  private CommandContainer commandContainer;

  @Override
  public void onLoad() {

    instance = this;
  }

  @Override
  public void onDisable() {

    // without the unregister, a PlugMan-style disable/enable cycle stacks a second
    // "list" container whose executor still references the disabled plugin instance
    if(commandContainer != null && plugin != null) {
      plugin.getCommandManager().unregisterCmd(commandContainer);
      commandContainer = null;
    }
    HandlerList.unregisterAll((Plugin)this);
  }

  @Override
  public void onEnable() {

    saveDefaultConfig();
    plugin = QuickShop.getInstance();
    getLogger().info("Registering the per shop permissions...");
    Bukkit.getPluginManager().registerEvents(this, this);
    commandContainer = CommandContainer
            .builder()
            .prefix("list")
            .description((locale)->plugin.text().of("addon.list.commands.list").forLocale(locale))
            .selectivePermission("quickshopaddon.list.self")
            .selectivePermission("quickshopaddon.list.other")
            .executor(new SubCommand_List(plugin))
            .build();
    plugin.getCommandManager().registerCmd(commandContainer);
  }
}
