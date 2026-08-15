package com.ghostchu.quickshop;

import com.ghostchu.quickshop.api.QuickShopProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared Bukkit static-harness for unit tests: wires Bukkit.getServicesManager so that
 * QuickShopAPI.getInstance()/getPluginInstance() resolve to the mocked plugin, which is
 * required because interfaces like Shop initialize NamespacedKeys through it.
 */
public final class MockBukkit {

  private MockBukkit() {

  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void install(final MockedStatic<Bukkit> bukkitStatic, final QuickShop plugin) {

    final PluginManager pluginManager = mock(PluginManager.class);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    bukkitStatic.when(Bukkit::isPrimaryThread).thenReturn(true);
    when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));

    final org.bukkit.plugin.Plugin bukkitPlugin = mock(org.bukkit.plugin.Plugin.class);
    when(bukkitPlugin.getName()).thenReturn("quickshophikari");

    final QuickShopProvider provider = mock(QuickShopProvider.class);
    when(provider.getApiInstance()).thenReturn(plugin);
    when(provider.getInstance()).thenReturn(bukkitPlugin);
    final RegisteredServiceProvider rsp = mock(RegisteredServiceProvider.class);
    when(rsp.getProvider()).thenReturn(provider);
    when(rsp.getPlugin()).thenReturn(bukkitPlugin);
    final ServicesManager servicesManager = mock(ServicesManager.class);
    when(servicesManager.getRegistration(QuickShopProvider.class)).thenReturn(rsp);
    bukkitStatic.when(Bukkit::getServicesManager).thenReturn(servicesManager);
  }
}
