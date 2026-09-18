package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Hopper;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R60 container-protection bypass fixes: hopper MINECARTS used
 * to slip past the plain-Hopper destination check and silently drain shops, pushing INTO
 * a shop container had no guard at all (stock/space pollution without purchase records),
 * dispensers bypassed the dropper gate, and fires could burn shop signs off unchecked.
 */
class ShopProtectionListenerMoveItemBypassTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();
  private SimpleShopManager shopManager;
  private ShopProtectionListener listener;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.getReloadManager())
            .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    final YamlDocument config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getBoolean(any(String.class))).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Boolean bool && bool;
    });
    lenient().when(config.getBoolean(any(String.class), any(Boolean.class))).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Boolean bool && bool;
    });
    lenient().when(plugin.getConfig()).thenReturn(config);

    shopManager = mock(SimpleShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private InventoryMoveItemEvent moveEvent(final Inventory destination, final Inventory source, final Inventory initiator) {

    final InventoryMoveItemEvent event = mock(InventoryMoveItemEvent.class);
    when(event.getDestination()).thenReturn(destination);
    when(event.getSource()).thenReturn(source);
    when(event.getInitiator()).thenReturn(initiator);
    return event;
  }

  private Inventory hopperInventoryWithHolder(final InventoryHolder holder) {

    final Inventory inv = mock(Inventory.class);
    when(inv.getHolder(false)).thenReturn(holder);
    lenient().when(inv.getHolder()).thenReturn(holder);
    return inv;
  }

  private Inventory shopChestInventory(final Location location) {

    final Inventory inv = mock(Inventory.class);
    lenient().when(inv.getLocation()).thenReturn(location);
    lenient().when(inv.getHolder(false)).thenReturn(mock(InventoryHolder.class));
    lenient().when(inv.getHolder()).thenReturn(mock(InventoryHolder.class));
    return inv;
  }

  private Shop shopAt(final Location location) {

    final Shop shop = mock(Shop.class);
    lenient().when(shopManager.getShopIncludeAttachedViaCache(location)).thenReturn(shop);
    return shop;
  }

  private Shop shopAttachedAt(final Location location) {

    final Shop shop = mock(Shop.class);
    lenient().when(shopManager.getShopIncludeAttached(location)).thenReturn(shop);
    return shop;
  }

  @Test
  void hopperMinecartExtractionFromShopStillCancels() {

    configValues.put("protect.hopper", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    // a hopper minecart below the shop chest: the destination holder is NOT a Hopper
    // block state, yet the extraction must still be cancelled
    final Inventory minecartInv = hopperInventoryWithHolder(mock(HopperMinecart.class));
    final Location shopLoc = new Location(null, 10, 64, 10);
    final Inventory shopChest = shopChestInventory(shopLoc);
    shopAt(shopLoc);
    final var event = moveEvent(minecartInv, shopChest, minecartInv);

    listener.onHopperMoveItem(event);

    verify(event).setCancelled(true);
  }

  @Test
  void hopperInjectionIntoShopCancels() {

    configValues.put("protect.hopper", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    // a hopper pushes items INTO the shop container: destination is the (shop) chest,
    // source is the hopper — the old guard only ever inspected the destination holder
    // and let the transfer pollute stock accounting
    final Location shopLoc = new Location(null, 11, 64, 11);
    final Inventory shopChest = shopChestInventory(shopLoc);
    shopAt(shopLoc);
    final Inventory hopperInv = hopperInventoryWithHolder(mock(Hopper.class));
    lenient().when(hopperInv.getLocation()).thenReturn(new Location(null, 12, 63, 12));
    final var event = moveEvent(shopChest, hopperInv, hopperInv);

    listener.onHopperMoveItem(event);

    verify(event).setCancelled(true);
  }

  @Test
  void hopperMinecartInjectionIntoShopCancels() {

    configValues.put("protect.hopper", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    final Location shopLoc = new Location(null, 13, 64, 13);
    final Inventory shopChest = shopChestInventory(shopLoc);
    shopAt(shopLoc);
    final Inventory minecartInv = hopperInventoryWithHolder(mock(HopperMinecart.class));
    lenient().when(minecartInv.getLocation()).thenReturn(new Location(null, 14, 63, 14));
    final var event = moveEvent(shopChest, minecartInv, minecartInv);

    listener.onHopperMoveItem(event);

    verify(event).setCancelled(true);
  }

  @Test
  void dispenserInitiatorIntoShopCancels() {

    configValues.put("protect.dropper", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    // a dispenser faced at the shop container fires the same move event with a
    // Dispenser initiator — previously only Dropper matched
    final Location shopLoc = new Location(null, 15, 64, 15);
    final Inventory shopChest = shopChestInventory(shopLoc);
    shopAt(shopLoc);
    final Inventory dispenserInv = hopperInventoryWithHolder(mock(Dispenser.class));
    final var event = moveEvent(shopChest, dispenserInv, dispenserInv);

    listener.onDropperMoveItem(event);

    verify(event).setCancelled(true);
  }

  @Test
  void hopperToHopperBetweenNonShopsStaysUntouched() {

    configValues.put("protect.hopper", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    // hopper -> hopper with no shop at either end: no cancellation either way
    final Inventory destHopper = hopperInventoryWithHolder(mock(Hopper.class));
    final Inventory srcHopper = hopperInventoryWithHolder(mock(Hopper.class));
    lenient().when(srcHopper.getLocation()).thenReturn(new Location(null, 16, 64, 16));
    lenient().when(destHopper.getLocation()).thenReturn(new Location(null, 17, 64, 17));
    final var event = moveEvent(destHopper, srcHopper, srcHopper);

    listener.onHopperMoveItem(event);

    verify(event, never()).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void blockBurnOfShopBlockCancelsWhenProtectBurnEnabled() {

    configValues.put("protect.burn", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    final Location shopLoc = new Location(null, 18, 64, 18);
    final Block block = mock(Block.class);
    lenient().when(block.getLocation()).thenReturn(shopLoc);
    shopAttachedAt(shopLoc);
    final BlockBurnEvent event = mock(BlockBurnEvent.class);
    when(event.getBlock()).thenReturn(block);

    listener.onBlockBurn(event);

    verify(event).setCancelled(true);
  }

  @Test
  void blockBurnIgnoredWhenProtectBurnDisabled() {

    configValues.put("protect.burn", Boolean.FALSE);
    listener = new ShopProtectionListener(plugin);

    final Location shopLoc = new Location(null, 19, 64, 19);
    final Block block = mock(Block.class);
    lenient().when(block.getLocation()).thenReturn(shopLoc);
    final BlockBurnEvent event = mock(BlockBurnEvent.class);
    when(event.getBlock()).thenReturn(block);

    listener.onBlockBurn(event);

    verify(event, never()).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
  }
}
