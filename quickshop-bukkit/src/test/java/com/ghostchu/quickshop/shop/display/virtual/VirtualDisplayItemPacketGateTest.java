package com.ghostchu.quickshop.shop.display.virtual;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.event.AbstractQSEvent;
import com.ghostchu.quickshop.api.event.packet.send.PacketHandlerSendSpawnEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.display.PacketFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R50 contracts for the virtual display packet path: with no listener registered the
 * per-packet events (spawn/meta/destroy) and the applicability check are skipped entirely
 * — the packets go straight out and isApplicableForPlayer answers its constructed default
 * (true); with a listener the events still fire and a cancellation suppresses the packet.
 */
class VirtualDisplayItemPacketGateTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private VirtualDisplayItemManager manager;
  private PacketFactory<Object> factory;
  private final Object spawnPacket = new Object();
  private final Object metaPacket = new Object();
  private final Object destroyPacket = new Object();
  private Player player;
  private PluginManager pluginManager;

  @BeforeEach
  void setUp() throws Exception {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    pluginManager = mock(PluginManager.class);
    bukkitStatic.when(Bukkit::isPrimaryThread).thenReturn(true);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    HandlerList.unregisterAll();

    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    // install() re-stubs getPluginManager with its own mock; route it to ours so the
    // registered-listener tests can observe the dispatch
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    bukkitStatic.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of());
    lenient().when(Bukkit.getViewDistance()).thenReturn(8);

    // AbstractDisplayItem binds PLUGIN at class-init; stub whichever mock instance
    // was captured if an earlier test class in this JVM already loaded it
    final QuickShop captured = (QuickShop)AbstractDisplayItemField.PLUGIN.get(null);
    lenient().when(captured.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getDouble(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1, Double.class));
    lenient().when(captured.getConfig()).thenReturn(config);

    factory = mock(PacketFactory.class);
    when(factory.createSpawnPacket(anyInt(), any())).thenReturn(spawnPacket);
    when(factory.createMetaDataPacket(anyInt(), any())).thenReturn(metaPacket);
    when(factory.createDestroyPacket(anyInt())).thenReturn(destroyPacket);
    when(factory.createVelocityPacket(anyInt())).thenReturn(null);

    manager = mock(VirtualDisplayItemManager.class);
    when(manager.generateEntityId()).thenReturn(1000);
    lenient().when(manager.packetHandler()).thenReturn(null);
    setFinalField(manager, "shopEntities", new ConcurrentHashMap<>());

    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    bukkitStatic.close();
    quickShopStatic.close();
  }

  private static void setFinalField(final Object target, final String name, final Object value) throws Exception {

    final Field field = VirtualDisplayItemManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private VirtualDisplayItem<?> displayItem() {

    final World world = mock(World.class);
    when(world.getName()).thenReturn("world");

    // a real ItemStack needs the server's RegistryAccess (ItemType registry), so the
    // stack is a mock covering exactly the clone/asOne/enchantments surface the
    // display-item construction reads
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);
    lenient().when(item.asOne()).thenReturn(item);
    lenient().when(item.getEnchantments()).thenReturn(Map.of());

    final Shop shop = mock(Shop.class);
    when(shop.getShopId()).thenReturn(11L);
    when(shop.getItem()).thenReturn(item);
    when(shop.bukkitLocation()).thenReturn(new Location(world, -8, -59, 3));

    return new VirtualDisplayItem<>(manager, factory, shop);
  }

  @Test
  void noListenersPacketsGoStraightOut() throws Exception {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final VirtualDisplayItem<?> item = displayItem();

    item.sendSpawnPacket(player);
    item.sendMetaPacket(player);
    item.sendDestroyPacket(player);

    // direct sends, same packet instances, zero Bukkit dispatch along the way
    verify(factory).sendPacket(player, spawnPacket);
    verify(factory).sendPacket(player, metaPacket);
    verify(factory).sendPacket(player, destroyPacket);
    verify(pluginManager, never()).callEvent(any(Event.class));
  }

  @Test
  void noListenersApplicabilityAnswersTrueWithoutDispatch() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final VirtualDisplayItem<?> item = displayItem();

    assertTrue(item.isApplicableForPlayer(player));
    verify(pluginManager, never()).callEvent(any(Event.class));
  }

  @Test
  void spawnPacketCancellationStillSuppressesSend() throws Exception {

    final AtomicReference<PacketHandlerSendSpawnEvent<Object>> seen = new AtomicReference<>();
    final RegisteredListener listener = new RegisteredListener(
            mock(Listener.class),
            (org.bukkit.plugin.EventExecutor)(executorListener, event)->{
              final PacketHandlerSendSpawnEvent<Object> spawnEvent = (PacketHandlerSendSpawnEvent<Object>)event;
              seen.set(spawnEvent);
              spawnEvent.setCancelled(true);
            },
            EventPriority.NORMAL,
            mock(Plugin.class),
            false);
    AbstractQSEvent.getHandlerList().register(listener);
    doAnswer(inv -> {
      listener.callEvent(inv.getArgument(0, Event.class));
      return null;
    }).when(pluginManager).callEvent(any(Event.class));

    final VirtualDisplayItem<?> item = displayItem();
    item.sendSpawnPacket(player);

    assertEquals(spawnPacket, seen.get().spawnPacket(), "the listener observed the real spawn packet");
    verify(factory, never()).sendPacket(any(Player.class), any());
  }

  /** Loads AbstractDisplayItem first so {@code PLUGIN.get(null)} never initializes it against this test's mock. */
  private static final class AbstractDisplayItemField {

    static final Field PLUGIN;

    static {
      try {
        Class.forName("com.ghostchu.quickshop.shop.display.AbstractDisplayItem");
        PLUGIN = Class.forName("com.ghostchu.quickshop.shop.display.AbstractDisplayItem").getDeclaredField("PLUGIN");
        PLUGIN.setAccessible(true);
      } catch(final ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
