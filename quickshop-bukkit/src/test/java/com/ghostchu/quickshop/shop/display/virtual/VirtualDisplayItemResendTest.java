package com.ghostchu.quickshop.shop.display.virtual;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.display.PacketFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Regression tests for the chunk-entrance display resend path (R22):
 * <ul>
 *   <li>{@link VirtualDisplayItem#sendFakeItem} emits exactly one
 *       destroy-spawn-meta packet sequence per player -- the chunk listener used to
 *       issue an extra destroy right before it, which duplicated the destroy that
 *       sendFakeItem already leads with;</li>
 *   <li>{@link VirtualDisplayItemManager#resendChunkDisplays} registers applicable
 *       players as senders, skips unspawned/inapplicable displays and sends nothing
 *       beyond the single sequence;</li>
 *   <li>{@link VirtualDisplayItemManager#withdrawChunkDisplays} destroys spawned
 *       displays and unregisters the sender.</li>
 * </ul>
 */
class VirtualDisplayItemResendTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private VirtualDisplayItemManager manager;
  private PacketFactory<Object> factory;
  private final Object spawnPacket = new Object();
  private final Object metaPacket = new Object();
  private final Object destroyPacket = new Object();
  private Player player;
  private UUID playerId;

  @BeforeEach
  void setUp() throws Exception {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    // AbstractDisplayItem's static init builds a NamespacedKey from the plugin instance
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    bukkitStatic.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
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
    playerId = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(playerId);
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  private static void setFinalField(final Object target, final String name, final Object value) throws Exception {

    final Field field = VirtualDisplayItemManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private VirtualDisplayItem<?> displayItem() throws Exception {

    final World world = mock(World.class);
    when(world.getName()).thenReturn("world");

    // a real ItemStack needs the server's RegistryAccess (ItemType registry), so the
    // stack is a mock covering exactly the clone/asOne/enchantments surface the
    // display-item construction reads
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);
    lenient().when(item.asOne()).thenReturn(item);
    lenient().when(item.getEnchantments()).thenReturn(java.util.Map.of());

    final Shop shop = mock(Shop.class);
    when(shop.getShopId()).thenReturn(7L);
    when(shop.getItem()).thenReturn(item);
    when(shop.bukkitLocation()).thenReturn(new Location(world, -8, -59, 3));

    return new VirtualDisplayItem<>(manager, factory, shop);
  }

  @Test
  void sendFakeItemEmitsExactlyOneDestroySpawnMetaSequence() throws Exception {

    final VirtualDisplayItem<?> item = displayItem();

    item.sendFakeItem(player);

    final InOrder order = inOrder(factory);
    order.verify(factory).sendPacket(player, destroyPacket);
    order.verify(factory).sendPacket(player, spawnPacket);
    order.verify(factory).sendPacket(player, metaPacket);
    // exactly three packets: the historic duplicate destroy (a 4th packet the client
    // no-ops on) must stay gone
    verify(factory, times(3)).sendPacket(any(Player.class), any());
    verify(factory, times(1)).sendPacket(player, destroyPacket);
  }

  @Test
  void resendChunkDisplaysRegistersApplicableAndSkipsTheRest() throws Exception {

    final VirtualDisplayItemManager bare = mock(VirtualDisplayItemManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    setFinalField(bare, "chunksMapping", new ConcurrentHashMap<>());

    final VirtualDisplayItem<?> sent = mockDisplay(true, true);
    final VirtualDisplayItem<?> notApplicable = mockDisplay(true, false);
    final VirtualDisplayItem<?> notSpawned = mockDisplay(false, true);
    bare.getChunksMapping().put(new com.ghostchu.quickshop.shop.SimpleShopChunk("world", 1, 2),
                                new java.util.ArrayList<>(List.of(sent, notApplicable, notSpawned)));

    bare.resendChunkDisplays(player, "world", 1, 2);

    verify(sent, times(1)).sendFakeItem(player);
    // the chunk-entrance path must not issue any standalone destroy: sendFakeItem
    // already opens with one
    verify(sent, never()).sendDestroyPacket(player);
    assertTrue(sent.getPacketSenders().contains(playerId));

    verify(notApplicable, never()).sendFakeItem(player);
    assertFalse(notApplicable.getPacketSenders().contains(playerId));
    verify(notSpawned, never()).sendFakeItem(player);

    // other chunks stay untouched
    bare.resendChunkDisplays(player, "other", 1, 2);
    verify(sent, times(1)).sendFakeItem(player);
  }

  @Test
  void withdrawChunkDisplaysDestroysAndUnregisters() throws Exception {

    final VirtualDisplayItemManager bare = mock(VirtualDisplayItemManager.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    setFinalField(bare, "chunksMapping", new ConcurrentHashMap<>());

    final VirtualDisplayItem<?> spawned = mockDisplay(true, true);
    final VirtualDisplayItem<?> unspawned = mockDisplay(false, true);
    spawned.getPacketSenders().add(playerId);
    bare.getChunksMapping().put(new com.ghostchu.quickshop.shop.SimpleShopChunk("world", 3, 4),
                                new java.util.ArrayList<>(List.of(spawned, unspawned)));

    bare.withdrawChunkDisplays(player, "world", 3, 4);

    verify(spawned, times(1)).sendDestroyPacket(player);
    assertFalse(spawned.getPacketSenders().contains(playerId));
    verify(unspawned, never()).sendDestroyPacket(player);
  }

  @SuppressWarnings("unchecked")
  private VirtualDisplayItem<?> mockDisplay(final boolean spawned, final boolean applicable) {

    final VirtualDisplayItem<?> item = mock(VirtualDisplayItem.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    // CALLS_REAL_METHODS leaves instance fields null; the senders set must be real
    try {
      final Field field = VirtualDisplayItem.class.getDeclaredField("packetSenders");
      field.setAccessible(true);
      field.set(item, new ConcurrentSkipListSet<>());
    } catch(final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    // doReturn style: when() on a CALLS_REAL_METHODS mock would run the real body
    // while capturing arguments (null matchers) and NPE on player.getUniqueId()
    org.mockito.Mockito.doReturn(spawned).when(item).isSpawned();
    org.mockito.Mockito.doReturn(applicable).when(item).isApplicableForPlayer(any(Player.class));
    org.mockito.Mockito.doNothing().when(item).sendFakeItem(any(Player.class));
    org.mockito.Mockito.doNothing().when(item).sendDestroyPacket(any(Player.class));
    return item;
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
