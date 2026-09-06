package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import com.ghostchu.quickshop.shop.datatype.HopperPersistentData;
import com.ghostchu.quickshop.shop.datatype.HopperPersistentDataType;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dropper;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R43 snapshot-gate sweep: the InventoryMoveItemEvent gates
 * (hopper destination / dropper initiator) answer their instanceof through Paper's live
 * holder {@code getHolder(false)} instead of the block-state-snapshot {@code getHolder()},
 * and the block-place gates (hopper/dropper PDC tagging, sign placement) check the
 * material before paying {@code getState()}. The owner-exclude PDC branch must keep
 * reading through the snapshot holder.
 */
class ShopProtectionListenerSnapshotGateTest {

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

  private Inventory chestInventory(final Location location) {

    final Inventory chest = mock(Inventory.class);
    lenient().when(chest.getLocation()).thenReturn(location);
    // plain non-hopper holder: the gate declines before any hopper logic
    lenient().when(chest.getHolder()).thenReturn(mock(InventoryHolder.class));
    lenient().when(chest.getHolder(false)).thenReturn(mock(InventoryHolder.class));
    return chest;
  }

  @Test
  void hopperGateAnswersThroughLiveHolderWithoutSnapshot() {

    configValues.put("protect.hopper", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    // hopper pushes into an ordinary chest: destination is not a hopper, the handler
    // must decline via the live holder and never pay the snapshot variant
    final Inventory destination = chestInventory(new Location(null, 1, 64, 1));
    final Inventory source = mock(Inventory.class);
    final var event = moveEvent(destination, source, destination);

    listener.onHopperMoveItem(event);

    verify(destination).getHolder(false);
    verify(destination, never()).getHolder();
    verify(event, never()).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void dropperGateAnswersThroughLiveHolderWithoutSnapshot() {

    configValues.put("protect.dropper", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    // hopper-driven move (initiator is the hopper, not a dropper): the dropper handler
    // declines through the live holder
    final Inventory destination = chestInventory(new Location(null, 2, 64, 2));
    final Inventory source = mock(Inventory.class);
    final Inventory hopperInitiator = mock(Inventory.class);
    lenient().when(hopperInitiator.getHolder()).thenReturn(mock(Hopper.class));
    lenient().when(hopperInitiator.getHolder(false)).thenReturn(mock(Hopper.class));
    final var event = moveEvent(destination, source, hopperInitiator);

    listener.onDropperMoveItem(event);

    verify(hopperInitiator).getHolder(false);
    verify(hopperInitiator, never()).getHolder();
    verify(event, never()).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void hopperPullFromShopCancelsWithoutSnapshotHolder() {

    configValues.put("protect.hopper", Boolean.TRUE);
    configValues.put("protect.hopper-owner-exclude", Boolean.FALSE);
    listener = new ShopProtectionListener(plugin);

    // a hopper pulls from a shop chest: destination is the hopper itself, the source
    // location resolves to a shop, owner-exclude is off -> cancel, and the snapshot
    // variant is never needed
    final Inventory hopperInv = mock(Inventory.class);
    final var hopperHolder = mock(Hopper.class);
    when(hopperInv.getHolder(false)).thenReturn(hopperHolder);
    when(hopperInv.getHolder()).thenReturn(hopperHolder);
    final Location shopLoc = new Location(null, 3, 64, 3);
    final Inventory shopChest = chestInventory(shopLoc);
    final var event = moveEvent(hopperInv, shopChest, hopperInv);

    final Shop shop = mock(Shop.class);
    when(shopManager.getShopIncludeAttachedViaCache(shopLoc)).thenReturn(shop);

    listener.onHopperMoveItem(event);

    verify(event).setCancelled(true);
    verify(hopperInv, never()).getHolder();
  }

  @Test
  void ownerExcludeBranchReadsPdcThroughSnapshotHolder() {

    configValues.put("protect.hopper", Boolean.TRUE);
    configValues.put("protect.hopper-owner-exclude", Boolean.TRUE);
    listener = new ShopProtectionListener(plugin);

    // the live holder's PDC carries no data; only the snapshot holder's PDC carries the
    // authorized player. The event must NOT be cancelled, proving the branch read the
    // snapshot holder (the live holder would answer null and fall through to cancel)
    final Inventory hopperInv = mock(Inventory.class);
    final var liveHolder = mock(Hopper.class);
    final var snapshotHolder = mock(Hopper.class);
    when(hopperInv.getHolder(false)).thenReturn(liveHolder);
    when(hopperInv.getHolder()).thenReturn(snapshotHolder);

    final var emptyPdc = mock(PersistentDataContainer.class);
    when(liveHolder.getPersistentDataContainer()).thenReturn(emptyPdc);
    when(emptyPdc.get(any(NamespacedKey.class), eq(HopperPersistentDataType.INSTANCE))).thenReturn(null);

    final var authorizedPlayer = UUID.nameUUIDFromBytes(new byte[]{7});
    final var dataPdc = mock(PersistentDataContainer.class);
    when(snapshotHolder.getPersistentDataContainer()).thenReturn(dataPdc);
    when(dataPdc.get(any(NamespacedKey.class), eq(HopperPersistentDataType.INSTANCE)))
            .thenReturn(new HopperPersistentData(authorizedPlayer));

    final Location shopLoc = new Location(null, 4, 64, 4);
    final Inventory shopChest = chestInventory(shopLoc);
    final var event = moveEvent(hopperInv, shopChest, hopperInv);

    final Shop shop = mock(Shop.class);
    when(shopManager.getShopIncludeAttachedViaCache(shopLoc)).thenReturn(shop);
    when(shop.playerAuthorize(eq(authorizedPlayer), any(com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission.class)))
            .thenReturn(true);

    listener.onHopperMoveItem(event);

    verify(event, never()).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
    verify(hopperInv, times(1)).getHolder();
    verify(hopperInv, times(1)).getHolder(false);
  }

  private BlockPlaceEvent placeEvent(final Material type, final Block placed, final Player player) {

    final BlockPlaceEvent event = mock(BlockPlaceEvent.class);
    when(event.getBlockPlaced()).thenReturn(placed);
    lenient().when(placed.getType()).thenReturn(type);
    lenient().when(event.getPlayer()).thenReturn(player);
    return event;
  }

  @Test
  void placeProtectedBlockSkipsStateForUnrelatedBlocks() {

    listener = new ShopProtectionListener(plugin);

    final Block placed = mock(Block.class);
    final Player player = mock(Player.class);
    final var event = placeEvent(Material.STONE, placed, player);

    listener.onPlaceProtectedBlock(event);

    verify(placed, never()).getState();
    verify(placed, never()).getBlockData();
  }

  @Test
  void placeProtectedBlockStillTagsHopperPlacement() {

    listener = new ShopProtectionListener(plugin);

    final Block placed = mock(Block.class);
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(new byte[]{8}));
    final var event = placeEvent(Material.HOPPER, placed, player);

    final var hopperState = mock(Hopper.class);
    when(placed.getState()).thenReturn(hopperState);
    final var pdc = mock(PersistentDataContainer.class);
    when(hopperState.getPersistentDataContainer()).thenReturn(pdc);
    when(placed.getBlockData()).thenReturn(mock(org.bukkit.block.data.BlockData.class));

    listener.onPlaceProtectedBlock(event);

    verify(pdc).set(any(NamespacedKey.class), eq(HopperPersistentDataType.INSTANCE), any(HopperPersistentData.class));
    verify(hopperState).update();
  }

  @Test
  void dropperPlacementStillTaggedAfterMaterialGate() {

    listener = new ShopProtectionListener(plugin);

    final Block placed = mock(Block.class);
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(new byte[]{9}));
    final var event = placeEvent(Material.DROPPER, placed, player);

    final var dropperState = mock(Dropper.class);
    when(placed.getState()).thenReturn(dropperState);
    final var pdc = mock(PersistentDataContainer.class);
    when(dropperState.getPersistentDataContainer()).thenReturn(pdc);
    when(placed.getBlockData()).thenReturn(mock(org.bukkit.block.data.BlockData.class));

    listener.onPlaceProtectedBlock(event);

    verify(pdc).set(any(NamespacedKey.class), eq(HopperPersistentDataType.INSTANCE), any(HopperPersistentData.class));
    verify(dropperState).update();
  }
}
