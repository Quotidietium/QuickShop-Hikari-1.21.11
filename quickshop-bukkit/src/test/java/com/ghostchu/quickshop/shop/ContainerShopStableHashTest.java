package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.event.AbstractQSEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the stable shop hashCode: the Lombok-generated hash read every
 * field through its getter, so one hashCode() dispatched several RETRIEVE events
 * (isDisableDisplay/shopState/shopType/getTaxAccount construct and call them) and
 * cloned the item twice (getItem) — paid by every Set/Map membership operation — and it
 * changed when a mutable field changed, which could strand entries in hash collections.
 * The hand-written hash is the final location field: constant-time, lifetime-stable,
 * and contract-consistent with the untouched equals().
 */
class ContainerShopStableHashTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private PluginManager pluginManager;
  private World world;

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
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-hash-test").toFile());
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(any(String.class))).thenReturn(false);
    lenient().when(config.getInt(anyString())).thenReturn(0);
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.platform()).thenReturn(mock(com.ghostchu.quickshop.platform.Platform.class));

    world = mock(World.class);
    when(world.getName()).thenReturn("world");
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    bukkitStatic.close();
    quickShopStatic.close();
  }

  private ContainerShop shop(final Location location) {

    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("hash-owner".getBytes()), "hash-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);
    lenient().when(item.getType()).thenReturn(org.bukkit.Material.DIAMOND);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    return new ContainerShop(
            plugin, -1L, location, 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), false, null,
            "Bukkit", "sym-hash", null,
            new HashMap<>(), benefit);
  }

  @Test
  void hashIsStableAndMembershipSurvivesMutations() {

    final ContainerShop shop = shop(new Location(world, 1, 64, 1));
    final int before = shop.hashCode();

    // shopName is one of the fields the old getter-based hash folded in: mutating it
    // used to re-hash the shop into a different bucket, stranding the set entry
    shop.setShopName("renamed");
    shop.setDirty();

    assertEquals(before, shop.hashCode());
    final Set<ContainerShop> pending = new HashSet<>();
    assertTrue(pending.add(shop));
    shop.setShopName("renamed-again");
    assertTrue(pending.contains(shop), "membership must survive field mutation");
    assertTrue(pending.remove(shop));
    assertTrue(pending.isEmpty());
  }

  @Test
  void hashCodeDispatchesNoEventsEvenWithListenersRegistered() {

    // make every QS event dispatch observable: a counting listener on the shared
    // HandlerList plus a dispatch through the mocked plugin manager
    final AtomicInteger dispatched = new AtomicInteger();
    final RegisteredListener counting = new RegisteredListener(
            mock(Listener.class),
            (final Listener listener, final Event event)->dispatched.incrementAndGet(),
            EventPriority.NORMAL, mock(Plugin.class), false);
    AbstractQSEvent.getHandlerList().register(counting);

    final ContainerShop shop = shop(new Location(world, 2, 64, 2));
    for(int i = 0; i < 10; i++) {
      // nothing to accumulate: the assertion is that no dispatch happens per call
      shop.hashCode();
    }

    assertEquals(0, dispatched.get(), "hashCode must not construct/dispatch RETRIEVE events");
    verify(pluginManager, org.mockito.Mockito.never()).callEvent(any(Event.class));
    HandlerList.unregisterAll();
  }

  @Test
  void equalShopsHashEqualAndDistinctLocationsDiffer() {

    final Location shared = new Location(world, 3, 64, 3);
    final ContainerShop a = shop(shared);
    final ContainerShop b = shop(shared);
    final ContainerShop elsewhere = shop(new Location(world, 4, 64, 4));

    assertTrue(a.equals(b) == b.equals(a));
    if(a.equals(b)) {
      assertEquals(a.hashCode(), b.hashCode());
    }
    assertNotEquals(a, elsewhere);
    assertEquals(a, a);
  }
}
