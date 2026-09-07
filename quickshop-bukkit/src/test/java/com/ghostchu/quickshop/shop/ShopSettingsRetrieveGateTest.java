package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.event.AbstractQSEvent;
import com.ghostchu.quickshop.api.event.settings.type.ShopStateEvent;
import com.ghostchu.quickshop.api.shop.state.ShopState;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermissionGroup;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R46 contracts: the RETRIEVE-phase settings getters (shop state/type/display flag/tax
 * account/player group/owner name/sign lines) skip building their event objects when no
 * listener is registered — ShopSettingEvent's updated value starts as the passed-in
 * value, so the un-listened outcome is provably the raw field — and still construct and
 * dispatch (with mutation) when a listener exists. The namespaced permission nodes memo
 * their strings: the plugin name is constant per run.
 */
class ShopSettingsRetrieveGateTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private org.bukkit.World world;
  private PluginManager pluginManager;
  private SimpleShopManager shopManager;

  @BeforeEach
  void setUp() throws java.io.IOException {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    pluginManager = mock(PluginManager.class);
    bukkitStatic.when(Bukkit::isPrimaryThread).thenReturn(true);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    HandlerList.unregisterAll();

    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-gate-test").toFile());
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(any(String.class))).thenReturn(false);
    lenient().when(config.getInt(org.mockito.ArgumentMatchers.anyString())).thenReturn(0);
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.platform()).thenReturn(mock(com.ghostchu.quickshop.platform.Platform.class));

    shopManager = mock(SimpleShopManager.class);
    lenient().when(shopManager.getCacheTaxAccount()).thenReturn(null);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
    final var permissionManager = mock(SimpleShopPermissionManager.class);
    lenient().when(plugin.getShopPermissionManager()).thenReturn(permissionManager);
    lenient().when(permissionManager.hasGroup(any(String.class))).thenReturn(true);

    world = mock(org.bukkit.World.class);
    when(world.getName()).thenReturn("world");
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    bukkitStatic.close();
    quickShopStatic.close();
  }

  private ContainerShop shop() {

    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.getType()).thenReturn(Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(1);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    lenient().when(item.clone()).thenReturn(item);
    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("gate-owner".getBytes()), "gate-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    return new ContainerShop(
            plugin, -1L, new org.bukkit.Location(world, 1, 64, 1), 10.0d, item,
            owner, false, SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), true, null,
            "Bukkit", "sym-gate", null,
            new HashMap<>(), benefit);
  }

  @Test
  void noListenersSettingsGettersReturnFieldValues() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final ContainerShop shop = shop();

    assertSame(SimpleShopManager.SELLING_TYPE, shop.shopType());
    assertSame(SimpleShopManager.ACTIVE_STATE, shop.shopState());
    // constructed with disableDisplay=true and no tax account: raw values, no event round trip
    assertTrue(shop.isDisableDisplay());
    assertEquals(null, shop.getTaxAccount());
    // group resolution without listeners: the stored group passes through untouched
    assertEquals(BuiltInShopPermissionGroup.EVERYONE.getNamespacedNode(), shop.getPlayerGroup(UUID.randomUUID()));
  }

  @Test
  void retrieveEventStillFiresAndMutatesWithListener() {

    final AtomicReference<ShopState> seen = new AtomicReference<>();
    final ShopState override = mock(ShopState.class);
    final RegisteredListener listener = new RegisteredListener(
            mock(Listener.class),
            (executorListener, event)->{
              final ShopStateEvent stateEvent = (ShopStateEvent)event;
              seen.set(stateEvent.old());
              stateEvent.updated(override);
            },
            EventPriority.NORMAL,
            mock(Plugin.class),
            false);
    AbstractQSEvent.getHandlerList().register(listener);
    doAnswer(inv -> {
      listener.callEvent(inv.getArgument(0, Event.class));
      return null;
    }).when(pluginManager).callEvent(any(Event.class));

    final ContainerShop shop = shop();
    assertSame(override, shop.shopState());
    assertSame(SimpleShopManager.ACTIVE_STATE, seen.get(), "the listener saw the raw field value");
    // unregistering restores the raw path
    HandlerList.unregisterAll();
    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    assertSame(SimpleShopManager.ACTIVE_STATE, shop.shopState());
  }

  @Test
  void taxAccountListenerOverrideStillApplies() {

    final var taxAccountOverride = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("tax-account".getBytes()), "tax", true);
    final RegisteredListener listener = new RegisteredListener(
            mock(Listener.class),
            (executorListener, event)->((com.ghostchu.quickshop.api.event.settings.type.ShopTaxAccountEvent)event)
                    .updated(taxAccountOverride),
            EventPriority.NORMAL,
            mock(Plugin.class),
            false);
    AbstractQSEvent.getHandlerList().register(listener);
    doAnswer(inv -> {
      listener.callEvent(inv.getArgument(0, Event.class));
      return null;
    }).when(pluginManager).callEvent(any(Event.class));

    final ContainerShop shop = shop();
    assertSame(taxAccountOverride, shop.getTaxAccount());
  }

  @Test
  void namespacedPermissionNodesAreMemoized() {

    try(final MockedStatic<com.ghostchu.quickshop.api.QuickShopAPI> api = mockStatic(com.ghostchu.quickshop.api.QuickShopAPI.class)) {
      final var bukkitPlugin = mock(org.bukkit.plugin.Plugin.class);
      final AtomicInteger resolutions = new AtomicInteger();
      api.when(com.ghostchu.quickshop.api.QuickShopAPI::getPluginInstance).thenAnswer(inv -> {
        resolutions.incrementAndGet();
        return bukkitPlugin;
      });
      when(bukkitPlugin.getName()).thenReturn("QuickShop-Hikari");

      final String groupFirst = BuiltInShopPermissionGroup.BLOCKED.getNamespacedNode();
      final String groupSecond = BuiltInShopPermissionGroup.BLOCKED.getNamespacedNode();
      assertSame(groupFirst, groupSecond, "the memo hands out the same string instance");
      assertNotNull(groupFirst);

      final String permissionFirst = BuiltInShopPermission.DELETE.getNamespacedNode();
      final String permissionSecond = BuiltInShopPermission.DELETE.getNamespacedNode();
      assertSame(permissionFirst, permissionSecond);

      // whichever constant this JVM warmed first, the second resolution must not re-run
      // the services chain (at most one resolution per constant across both calls)
      assertTrue(resolutions.get() <= 2, "two constants resolve at most twice, got " + resolutions.get());
    }
  }
}
