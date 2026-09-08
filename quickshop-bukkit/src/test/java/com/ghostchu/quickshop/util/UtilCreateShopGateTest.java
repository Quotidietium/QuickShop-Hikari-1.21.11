package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.permission.PermissionManager;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the createShop gate order: the default interaction.yml maps
 * standing left-clicks on shopblocks/containers to TRADE_INTERACTION, so every held-item
 * block punch server-wide lands in createShop. The cheap discriminating gates (block
 * null, game mode, air item, canBeShop, the quick-create kill switches, permission) run
 * first; the item clone, the stack clamp and the QUser construction wait until a shop
 * creation is actually possible. The two config kill switches snapshot through
 * Util.initialize() (the registered reload hook), falling back to live reads before it.
 */
class UtilCreateShopGateTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private YamlDocument config;
  private PermissionManager perm;
  private Player player;
  private World world;
  private final Map<String, List<String>> listValues = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() throws Exception {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    config = mock(YamlDocument.class);
    listValues.clear();
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getStringList(anyString()))
            .thenAnswer(inv -> new ArrayList<>(listValues.getOrDefault(inv.getArgument(0, String.class), List.of())));
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-create-gate").toFile());

    // Util.initialize touches AbstractDisplayItem.refreshConfigSnapshots(), whose class
    // init builds a NamespacedKey from the java-plugin (must be non-null and lowercase)
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    perm = mock(PermissionManager.class);
    lenient().when(perm.hasPermission(any(Player.class), anyString())).thenReturn(false);
    lenient().when(plugin.perm()).thenReturn(perm);

    // Tag interface fields initialize through Bukkit.getTag on first use
    bukkitStatic.when(() -> Bukkit.getTag(any(String.class), any(org.bukkit.NamespacedKey.class), any(Class.class)))
            .thenAnswer(inv -> mock(Tag.class));

    player = mock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    lenient().when(player.getName()).thenReturn("Steve");
    lenient().when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);

    world = mock(World.class);
    lenient().when(world.getName()).thenReturn("world");

    Util.initialize();

    // shoppable chests so the deep-walk cases clear canBeShop. Injecting the static set
    // directly: populate through initialize() would need Material.matchMaterial, whose
    // Registry static init needs a live server
    shoppables().add(Material.CHEST);
  }

  @SuppressWarnings("unchecked")
  private Set<Material> shoppables() throws Exception {

    final Field field = Util.class.getDeclaredField("SHOPABLES");
    field.setAccessible(true);
    return (Set<Material>)field.get(null);
  }

  @AfterEach
  void tearDown() throws Exception {

    // restore the universal defaults (no shoppable materials, no kill switch) so later
    // test classes observe the same statics as before this suite ran
    listValues.clear();
    shoppables().clear();
    setSnapshot(null);
    Util.initialize();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  private void setSnapshot(final Boolean value) throws Exception {

    final Field field = Util.class.getDeclaredField("quickCreateDisabledSnapshot");
    field.setAccessible(true);
    field.set(null, value);
  }

  private Block block(final Material material, final BlockState state) {

    final Block block = mock(Block.class);
    lenient().when(block.getType()).thenReturn(material);
    lenient().when(block.getWorld()).thenReturn(world);
    lenient().when(block.getLocation()).thenReturn(new Location(world, 10, 64, 10));
    lenient().when(block.getX()).thenReturn(10);
    lenient().when(block.getY()).thenReturn(64);
    lenient().when(block.getZ()).thenReturn(10);
    lenient().when(block.getState(false)).thenReturn(state);
    return block;
  }

  private BlockState containerState() {

    final BlockState state = mock(BlockState.class,
            org.mockito.Mockito.withSettings().extraInterfaces(InventoryHolder.class));
    lenient().when(((InventoryHolder)state).getInventory()).thenReturn(mock(org.bukkit.inventory.Inventory.class));
    return state;
  }

  private ItemStack heldItem(final Material material, final int amount) {

    final ItemStack clone = mock(ItemStack.class);
    lenient().when(clone.getType()).thenReturn(material);
    lenient().when(clone.getAmount()).thenReturn(amount);

    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.getType()).thenReturn(material);
    lenient().when(item.getAmount()).thenReturn(amount);
    lenient().when(item.clone()).thenReturn(clone);
    return item;
  }

  @Test
  void dirtPunchClonesNothingAndBuildsNoUser() {

    final Block dirt = block(Material.DIRT, mock(BlockState.class));
    final ItemStack pickaxe = heldItem(Material.IRON_PICKAXE, 1);

    assertFalse(Util.createShop(player, dirt, BlockFace.NORTH, EquipmentSlot.HAND, pickaxe));

    // the dominant server-wide shape (held-item punch on a non-container block) must
    // not pay the item clone or the QUser construction
    verify(pickaxe, never()).clone();
    verify(player, never()).getName();
    verify(player, never()).getUniqueId();
  }

  @Test
  void airItemSkipsCloneAndShopBlockChecks() {

    final Block chest = block(Material.CHEST, containerState());
    final ItemStack air = heldItem(Material.AIR, 0);

    assertFalse(Util.createShop(player, chest, BlockFace.NORTH, EquipmentSlot.HAND, air));

    verify(air, never()).clone();
    // the air gate precedes canBeShop, so the container classification never runs
    verify(chest, never()).getState(false);
  }

  @Test
  void creativeModeSkipsClone() {

    when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
    final Block chest = block(Material.CHEST, containerState());
    final ItemStack pickaxe = heldItem(Material.IRON_PICKAXE, 1);

    assertFalse(Util.createShop(player, chest, BlockFace.NORTH, EquipmentSlot.HAND, pickaxe));

    verify(pickaxe, never()).clone();
  }

  @Test
  void noPermissionSkipsCloneButWalksTheGates() {

    final Block chest = block(Material.CHEST, containerState());
    final ItemStack dirtStack = heldItem(Material.DIRT, 3);

    assertFalse(Util.createShop(player, chest, BlockFace.NORTH, EquipmentSlot.HAND, dirtStack));

    // permission is consulted (sell then buy)...
    verify(perm, times(1)).hasPermission(player, "quickshop.create.sell");
    verify(perm, times(1)).hasPermission(player, "quickshop.create.buy");
    // ...but the clone waits until permission exists
    verify(dirtStack, never()).clone();
  }

  @Test
  void killSwitchSnapshotSkipsConfigReadsAndPermission() throws Exception {

    setSnapshot(true);

    final Block chest = block(Material.CHEST, containerState());
    final ItemStack dirtStack = heldItem(Material.DIRT, 3);

    clearInvocations(config);
    assertFalse(Util.createShop(player, chest, BlockFace.NORTH, EquipmentSlot.HAND, dirtStack));

    // the snapshotted kill switch answers without a config walk or a permission lookup
    verify(config, times(0)).getBoolean(anyString());
    verify(perm, never()).hasPermission(any(Player.class), anyString());
  }

  @Test
  void killSwitchFallsBackToLiveReadsBeforeInitialize() throws Exception {

    setSnapshot(null);
    when(config.getBoolean("disable-quick-create")).thenReturn(true);
    clearInvocations(config);

    final Block chest = block(Material.CHEST, containerState());
    final ItemStack dirtStack = heldItem(Material.DIRT, 3);

    assertFalse(Util.createShop(player, chest, BlockFace.NORTH, EquipmentSlot.HAND, dirtStack));

    verify(config).getBoolean("disable-quick-create");
    // short-circuit: the second legacy key is not consulted once the first is true
    verify(config, never()).getBoolean("shop.disable-quick-create");
    verify(perm, never()).hasPermission(any(Player.class), anyString());
  }

  @Test
  void killSwitchReloadRefreshesTheSnapshot() throws Exception {

    when(config.getBoolean("shop.disable-quick-create")).thenReturn(true);
    Util.initialize();
    // initialize() clears the shoppable set and re-reads config; re-inject the test set
    shoppables().add(Material.CHEST);
    final Block chest = block(Material.CHEST, containerState());
    final ItemStack dirtStack = heldItem(Material.DIRT, 3);
    assertFalse(Util.createShop(player, chest, BlockFace.NORTH, EquipmentSlot.HAND, dirtStack));
    verify(perm, never()).hasPermission(any(Player.class), anyString());

    // admin edit + /qs reload re-runs initialize(): the switch clears and the walk
    // proceeds past the kill gate (denied at permission instead)
    when(config.getBoolean("shop.disable-quick-create")).thenReturn(false);
    Util.initialize();
    shoppables().add(Material.CHEST);
    assertFalse(Util.createShop(player, chest, BlockFace.NORTH, EquipmentSlot.HAND, dirtStack));
    verify(perm, times(1)).hasPermission(player, "quickshop.create.sell");
  }

  @Test
  void grantedWalkClonesExactlyOnceBeforeTheDoubleChestGate() {

    final Block chest = block(Material.CHEST, containerState());
    final org.bukkit.block.data.type.Chest doubleData = mock(org.bukkit.block.data.type.Chest.class);
    lenient().when(doubleData.getType()).thenReturn(org.bukkit.block.data.type.Chest.Type.LEFT);
    lenient().when(chest.getBlockData()).thenReturn(doubleData);
    // build the neighbour before the stubbing: mock setup inside an ongoing when() chain
    // trips Mockito's unfinished-stubbing detection
    final Block airNeighbour = block(Material.AIR, mock(BlockState.class));
    lenient().when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(airNeighbour);

    when(perm.hasPermission(any(Player.class), anyString())).thenAnswer(
            inv -> "quickshop.create.sell".equals(inv.getArgument(1, String.class)));

    final var text = mock(com.ghostchu.quickshop.api.localization.text.TextManager.class);
    final var message = mock(com.ghostchu.quickshop.api.localization.text.Text.class);
    lenient().when(text.of(any(org.bukkit.command.CommandSender.class),
                           org.mockito.ArgumentMatchers.eq("no-double-chests"))).thenReturn(message);
    lenient().when(plugin.text()).thenReturn(text);

    final ItemStack dirtStack = heldItem(Material.DIRT, 3);

    assertFalse(Util.createShop(player, chest, BlockFace.NORTH, EquipmentSlot.HAND, dirtStack));

    // the deferred clone runs exactly once on the real creation walk, before the
    // double-chest permission gate denies with the same message as before
    verify(dirtStack, times(1)).clone();
    verify(text).of(player, "no-double-chests");
  }

  @Test
  void survivalModeGatePrecedesEverything() {

    when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
    final Block dirt = block(Material.DIRT, mock(BlockState.class));
    final ItemStack pickaxe = heldItem(Material.IRON_PICKAXE, 1);

    assertFalse(Util.createShop(player, dirt, BlockFace.NORTH, EquipmentSlot.HAND, pickaxe));

    verify(pickaxe, never()).clone();
    verify(dirt, never()).getState(false);
  }
}
