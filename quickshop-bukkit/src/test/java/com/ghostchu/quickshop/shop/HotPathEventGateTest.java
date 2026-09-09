package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.event.AbstractQSEvent;
import com.ghostchu.quickshop.api.event.economy.EconomyTransactionEvent;
import com.ghostchu.quickshop.api.event.inventory.InventoryTransactionEvent;
import com.ghostchu.quickshop.api.event.management.ShopClickEvent;
import com.ghostchu.quickshop.api.event.management.ShopPermissionCheckEvent;
import com.ghostchu.quickshop.api.event.settings.type.ShopSignLinesEvent;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperIterator;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R50 contracts: the remaining hot-path QuickShop event constructions (per-trade tax /
 * purchase / success / economy / inventory-transaction events, stock-calculation events,
 * sign-line events, the three-phase shop click, permission checks and
 * Util.fireCancellableEvent) are gated on {@link AbstractQSEvent#hasListeners()}: with no
 * listener the construction is skipped and the un-listened outcome is provably identical
 * (nothing can cancel or observe the event); with a listener the event is still built,
 * dispatched and its mutation honored.
 */
class HotPathEventGateTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private World world;
  private PluginManager pluginManager;
  private SimpleShopPermissionManager permissionManager;

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
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-hot-gate-test").toFile());
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getInt(anyString())).thenReturn(0);
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.platform()).thenReturn(mock(com.ghostchu.quickshop.platform.Platform.class));

    final var shopManager = mock(SimpleShopManager.class);
    lenient().when(shopManager.getCacheTaxAccount()).thenReturn(null);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
    permissionManager = mock(SimpleShopPermissionManager.class);
    lenient().when(permissionManager.hasGroup(anyString())).thenReturn(true);
    lenient().when(plugin.getShopPermissionManager()).thenReturn(permissionManager);

    // the shop-click handler resolves the clicker's locale before the (early-returning
    // under this harness) sign refresh
    final var textManager = mock(com.ghostchu.quickshop.api.localization.text.TextManager.class);
    lenient().when(plugin.text()).thenReturn(textManager);
    lenient().when(plugin.getTextManager()).thenReturn(textManager);

    world = mock(World.class);
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
            UUID.nameUUIDFromBytes("hot-gate-owner".getBytes()), "hot-gate-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    return new ContainerShop(
            plugin, -1L, new org.bukkit.Location(world, 1, 64, 1), 10.0d, item,
            owner, false, SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), true, null,
            "Bukkit", "sym-hot-gate", null,
            new HashMap<>(), benefit);
  }

  private RegisteredListener register(final org.bukkit.plugin.EventExecutor executor) {

    final RegisteredListener listener = new RegisteredListener(
            mock(Listener.class), executor, EventPriority.NORMAL, mock(Plugin.class), false);
    AbstractQSEvent.getHandlerList().register(listener);
    doAnswer(inv -> {
      listener.callEvent(inv.getArgument(0, Event.class));
      return null;
    }).when(pluginManager).callEvent(any(Event.class));
    return listener;
  }

  @Test
  void noListenersPlayerAuthorizeAnswersGroupValueWithoutDispatch() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final ContainerShop shop = shop();
    final UUID stranger = UUID.randomUUID();

    when(permissionManager.hasPermission(anyString(), any(Plugin.class), anyString())).thenReturn(true);
    assertTrue(shop.playerAuthorize(stranger, BuiltInShopPermission.PURCHASE));

    when(permissionManager.hasPermission(anyString(), any(Plugin.class), anyString())).thenReturn(false);
    assertFalse(shop.playerAuthorize(stranger, BuiltInShopPermission.PURCHASE));

    // the owner check short-circuits before any group resolution
    assertTrue(shop.playerAuthorize(shop.getOwner().getUniqueId(), BuiltInShopPermission.PURCHASE));

    verify(pluginManager, never()).callEvent(any(Event.class));
  }

  @Test
  void playerAuthorizeListenerOverrideStillApplies() {

    final AtomicReference<Boolean> seen = new AtomicReference<>();
    // defensive cast: the shared HandlerList also delivers the RETRIEVE-phase group
    // event getPlayerGroup fires before the permission check itself
    register((executorListener, event)->{
      if(event instanceof final ShopPermissionCheckEvent check) {
        seen.set(check.hasPermission());
        check.hasPermission(false);
      }
    });
    when(permissionManager.hasPermission(anyString(), any(Plugin.class), anyString())).thenReturn(true);

    final ContainerShop shop = shop();
    assertFalse(shop.playerAuthorize(UUID.randomUUID(), BuiltInShopPermission.PURCHASE));
    assertTrue(seen.get(), "the listener observed the computed group value before overriding");
  }

  @Test
  void noListenersShopClickSkipsWholeEventBlock() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final ContainerShop shop = shop();
    final Player clicker = mock(Player.class);
    lenient().when(clicker.getUniqueId()).thenReturn(UUID.randomUUID());
    // isLoaded() is false under the mocked server, so the sign refresh inside onClick
    // early-returns and the op isolates the event block
    shop.onClick(clicker);
    verify(pluginManager, never()).callEvent(any(Event.class));
  }

  @Test
  void shopClickPhasesStillFireWithListener() {

    final AtomicInteger delivered = new AtomicInteger();
    register((executorListener, event)->{
      if(event instanceof ShopClickEvent) {
        delivered.incrementAndGet();
      }
    });

    final ContainerShop shop = shop();
    final Player clicker = mock(Player.class);
    lenient().when(clicker.getUniqueId()).thenReturn(UUID.randomUUID());
    shop.onClick(clicker);

    // PRE, MAIN and POST phases all reach a registered listener
    assertEquals(3, delivered.get());
  }

  @Test
  void noListenersInventoryTransactionSkipsConstructionEvent() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final InventoryWrapper from = mock(InventoryWrapper.class);
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);

    final SimpleInventoryTransaction tx = SimpleInventoryTransaction.builder()
            .from(from).item(item).amount(1).build();
    assertSame(from, tx.getFrom());
    verify(pluginManager, never()).callEvent(any(Event.class));
  }

  @Test
  void inventoryTransactionEventStillFiresWithListener() {

    final AtomicReference<InventoryTransactionEvent> seen = new AtomicReference<>();
    register((executorListener, event)->seen.set((InventoryTransactionEvent)event));

    final InventoryWrapper from = mock(InventoryWrapper.class);
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);

    final SimpleInventoryTransaction tx = SimpleInventoryTransaction.builder()
            .from(from).item(item).amount(1).build();
    assertSame(tx, seen.get().getTransaction());
  }

  @Test
  void noListenersEconomyTransactionSkipsConstructionEvent() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final var economyManager = mock(com.ghostchu.quickshop.api.economy.EconomyManager.class);
    final var provider = mock(com.ghostchu.quickshop.api.economy.EconomyProvider.class);
    lenient().when(plugin.getEconomyManager()).thenReturn(economyManager);
    lenient().when(economyManager.provider()).thenReturn(provider);

    final var to = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("eco-to".getBytes()), "eco-to", true);
    final var transaction = com.ghostchu.quickshop.economy.transaction.QSEconomyTransaction.builder()
            .amount(BigDecimal.ONE).to(to).build();
    assertEquals(0, BigDecimal.ONE.compareTo(transaction.amount()));
    verify(pluginManager, never()).callEvent(any(Event.class));
  }

  @Test
  void economyTransactionEventStillFiresWithListener() {

    final var economyManager = mock(com.ghostchu.quickshop.api.economy.EconomyManager.class);
    final var provider = mock(com.ghostchu.quickshop.api.economy.EconomyProvider.class);
    lenient().when(plugin.getEconomyManager()).thenReturn(economyManager);
    lenient().when(economyManager.provider()).thenReturn(provider);

    final AtomicReference<EconomyTransactionEvent> seen = new AtomicReference<>();
    register((executorListener, event)->seen.set((EconomyTransactionEvent)event));

    final var to = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("eco-to".getBytes()), "eco-to", true);
    final var transaction = com.ghostchu.quickshop.economy.transaction.QSEconomyTransaction.builder()
            .amount(BigDecimal.ONE).to(to).build();
    assertSame(transaction, seen.get().getTransaction());
  }

  @Test
  void fireCancellableEventShortCircuitsQsEventsWithoutListeners() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final ContainerShop shop = shop();
    final var event = new com.ghostchu.quickshop.api.event.management.ShopCreateEvent(
            com.ghostchu.quickshop.api.event.Phase.PRE_CANCELLABLE, null,
            shop.getOwner(), shop.bukkitLocation());

    assertFalse(com.ghostchu.quickshop.util.Util.fireCancellableEvent(event));
    verify(pluginManager, never()).callEvent(any(Event.class));
  }

  @Test
  void fireCancellableEventHonoursCancellingListener() {

    register((executorListener, event)->
            ((com.ghostchu.quickshop.api.event.QSCancellable)event).setCancelled(true, (net.kyori.adventure.text.Component)null));

    final ContainerShop shop = shop();
    final var event = new com.ghostchu.quickshop.api.event.management.ShopCreateEvent(
            com.ghostchu.quickshop.api.event.Phase.PRE_CANCELLABLE, null,
            shop.getOwner(), shop.bukkitLocation());

    assertTrue(com.ghostchu.quickshop.util.Util.fireCancellableEvent(event));
    verify(pluginManager, times(1)).callEvent(any(Event.class));
  }

  @Test
  void noListenersRemainingSpaceSkipsCalculationEvent() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final ContainerShop shop = shop();

    // matcher never matches: every slot counts as free space, value cross-checked
    // against a direct countSpace over the same wrapper
    final var matcher = mock(com.ghostchu.quickshop.api.shop.ItemMatcher.class);
    lenient().when(matcher.matches(any(ItemStack.class), any(ItemStack.class))).thenReturn(false);
    lenient().when(plugin.getItemMatcher()).thenReturn(matcher);

    // null slots read as free space; each iterator() call hands out a fresh pass
    final var wrapper = mock(InventoryWrapper.class);
    final ItemStack[] slots = new ItemStack[2];
    lenient().when(wrapper.iterator())
            .thenAnswer(unused->InventoryWrapperIterator.ofItemStacks(slots));

    final int expected = com.ghostchu.quickshop.util.Util.countSpace(wrapper, shop);
    final int actual = shop.getRemainingSpace(wrapper);

    assertEquals(expected, actual);
    verify(pluginManager, never()).callEvent(any(Event.class));
  }

  @Test
  void signLinesEventUpdatedDefaultsToPassedLines() {

    final ContainerShop shop = shop();
    final var lines = java.util.List.<net.kyori.adventure.text.Component>of(net.kyori.adventure.text.Component.text("l1"));
    final ShopSignLinesEvent event = new ShopSignLinesEvent(
            com.ghostchu.quickshop.api.event.Phase.POST, shop, lines);
    assertSame(lines, event.updated(), "no listener can change updated(), so the default is the passed list");
  }
}
