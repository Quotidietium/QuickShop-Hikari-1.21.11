package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.util.Util;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R32 block-listener snapshots: {@code shop.disable-super-tool}
 * and {@code shop.allow-owner-break-shop-sign} were re-read inside break-event branches;
 * both now snap at construction and flip through reloadModule().
 */
class BlockListenerSnapshotTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Util> utilStatic;
  private QuickShop plugin;
  private com.ghostchu.quickshop.shop.SimpleShopManager shopManager;
  private BlockListener listener;
  private Shop shop;
  private Player creator;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();
  /** Locations need a resolvable world: callers evaluate loc.getBlock() up front. */
  private org.bukkit.World worldStub;
  private Block fillBlock;
  private Block currentAttached;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    utilStatic = mockStatic(Util.class);
    when(Util.canBeShop(any(Block.class))).thenAnswer(inv -> inv.getArgument(0, Block.class).getType() == Material.CHEST);
    when(Util.isWallSign(any(Material.class))).thenReturn(Boolean.TRUE);
    when(Util.getAttached(any(Block.class))).thenAnswer(inv -> currentAttached);
    worldStub = mock(org.bukkit.World.class);
    fillBlock = mock(Block.class);
    lenient().when(worldStub.getBlockAt(any(org.bukkit.Location.class))).thenReturn(fillBlock);
    lenient().when(worldStub.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(fillBlock);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(plugin.getReloadManager())
            .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    final YamlDocument config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getBoolean(anyString())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Boolean bool && bool;
    });
    lenient().when(plugin.getConfig()).thenReturn(config);

    shopManager = mock(com.ghostchu.quickshop.shop.SimpleShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
    lenient().when(shopManager.getInteractiveManager())
            .thenReturn(mock(com.ghostchu.quickshop.api.shop.ShopManager.InteractiveManager.class));

    lenient().when(plugin.perm())
            .thenReturn(mock(com.ghostchu.quickshop.permission.PermissionManager.class));
    final var text = mock(com.ghostchu.quickshop.localization.text.SimpleTextManager.class);
    lenient().when(plugin.text()).thenReturn(text);
    final var message = mock(com.ghostchu.quickshop.localization.text.SimpleTextManager.Text.class);
    lenient().when(text.of(any(org.bukkit.command.CommandSender.class), anyString())).thenReturn(message);

    shop = mock(Shop.class);
    final UUID ownerId = UUID.nameUUIDFromBytes(new byte[]{7});
    final var owner = mock(com.ghostchu.quickshop.api.obj.QUser.class);
    lenient().when(owner.getUniqueId()).thenReturn(ownerId);
    lenient().when(shop.getOwner()).thenReturn(owner);
    lenient().when(shop.playerAuthorize(any(UUID.class), any(com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission.class)))
            .thenReturn(true);

    creator = mock(Player.class);
    lenient().when(creator.getUniqueId()).thenReturn(ownerId);
    lenient().when(creator.getName()).thenReturn("tester");
    lenient().when(creator.getGameMode()).thenReturn(GameMode.SURVIVAL);
    final org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
    lenient().when(creator.getInventory()).thenReturn(inventory);
    final ItemStack goldenAxe = mock(ItemStack.class);
    lenient().when(goldenAxe.getType()).thenReturn(Material.GOLDEN_AXE);
    lenient().when(inventory.getItemInMainHand()).thenReturn(goldenAxe);
  }
  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
    utilStatic.close();
  }

  private BlockBreakEvent breakEvent(final Material material) {

    final int salt = material == Material.CHEST ? 11 : 22;
    final Location loc = new Location(worldStub, salt, 60, 70);
    final Block block = mock(Block.class);
    lenient().when(block.getType()).thenReturn(material);
    lenient().when(block.getLocation()).thenReturn(loc);
    final BlockBreakEvent event = mock(BlockBreakEvent.class);
    lenient().when(event.getBlock()).thenReturn(block);
    lenient().when(event.getPlayer()).thenReturn(creator);
    if(material == Material.CHEST) {
      lenient().when(shopManager.getShopIncludeAttached(loc)).thenReturn(shop);
      lenient().when(shopManager.getShop(loc)).thenReturn(shop);
    } else {
      final Location attachedLoc = new Location(worldStub, salt + 1, 60, 70);
      final Block attached = mock(Block.class);
      lenient().when(attached.getLocation()).thenReturn(attachedLoc);
      currentAttached = attached;
      lenient().when(shopManager.getShop(attachedLoc)).thenReturn(shop);
      lenient().when(shopManager.getShopIncludeAttached(attachedLoc)).thenReturn(shop);
    }
    return event;
  }

  @Test
  void superToolDisabledCancelsCreativeAxeBreakAndKeepsTheShop() {

    configValues.put("shop.disable-super-tool", Boolean.TRUE);
    lenient().when(creator.getGameMode()).thenReturn(GameMode.CREATIVE);
    listener = new BlockListener(plugin);

    final BlockBreakEvent event = breakEvent(Material.CHEST);
    listener.onBreak(event);

    verify(event).setCancelled(true);
    verify(shopManager, never()).deleteShop(any(Shop.class));
  }

  @Test
  void superToolEnabledDeletesTheShopWithoutCancelling() {

    // flag absent == false (original one-arg getBoolean semantics)
    lenient().when(creator.getGameMode()).thenReturn(GameMode.CREATIVE);
    listener = new BlockListener(plugin);

    final BlockBreakEvent event = breakEvent(Material.CHEST);
    listener.onBreak(event);

    verify(shopManager).deleteShop(shop);
    verify(event, never()).setCancelled(true);
  }

  @Test
  void ownerSignBreakAllowFlagFlipsThroughReload() {

    listener = new BlockListener(plugin);

    // absent flag == false: the sign stays even when the owner swings
    final BlockBreakEvent denied = breakEvent(Material.OAK_WALL_SIGN);
    listener.onBreak(denied);
    verify(denied).setCancelled(true);

    configValues.put("shop.allow-owner-break-shop-sign", Boolean.TRUE);
    listener.reloadModule();

    final BlockBreakEvent allowed = breakEvent(Material.OAK_WALL_SIGN);
    listener.onBreak(allowed);
    verify(allowed, never()).setCancelled(true);
  }
}
