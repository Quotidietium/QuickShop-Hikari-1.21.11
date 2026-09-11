package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.event.AbstractQSEvent;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.localization.text.Text;
import com.ghostchu.quickshop.api.localization.text.TextManager;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.permission.PermissionManager;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import dev.dejvokep.boostedyaml.YamlDocument;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the click-entry measurement collapse: the default interaction
 * mapping routes every shop click through ShopUtil.sellToShop/buyFromShop, which used to
 * re-measure the same unchanged quantities several times per interaction (the info
 * panel's duplicate stock/space scan, an explicit sign render that onClick repeats in
 * the same locale, the out-of-space/stock gate re-derived by the cap calculation).
 * These tests pin the collapsed call counts and the listener-gated render contract:
 * without listeners the explicit render is skipped (onClick provably renders the same
 * locale), with a listener registered every historic render stays observable.
 */
class ShopUtilEntryCollapseTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private SimpleShopManager manager;
  private Shop shop;
  private Player player;
  private World world;

  @BeforeEach
  void setUp() throws Exception {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    HandlerList.unregisterAll();

    final YamlDocument config = mock(YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getBoolean(anyString(), anyBoolean())).thenReturn(false);
    lenient().when(config.getString(anyString())).thenReturn(null);
    lenient().when(config.getString(anyString(), anyString())).thenReturn(null);
    lenient().when(plugin.getConfig()).thenReturn(config);

    // Util.playClickSound reads the static Util.plugin field; hand it the same mock so
    // the config kill-switch (false) silences the sound without a live server
    final java.lang.reflect.Field utilPlugin = Util.class.getDeclaredField("plugin");
    utilPlugin.setAccessible(true);
    utilPlugin.set(null, plugin);

    // text pipeline answers empty components: every chat line no-ops, isolating the
    // measurement/render work from chat traffic
    final TextManager textManager = mock(TextManager.class);
    final Text text = mock(Text.class);
    lenient().when(text.forLocale(anyString())).thenReturn(Component.empty());
    lenient().when(text.forLocale()).thenReturn(Component.empty());
    lenient().when(textManager.of(anyString(), any(Object[].class))).thenReturn(text);
    lenient().when(textManager.of(anyString())).thenReturn(text);
    lenient().when(textManager.of(any(java.util.UUID.class), anyString(), any(Object[].class))).thenReturn(text);
    lenient().when(textManager.of(any(org.bukkit.command.CommandSender.class), anyString(), any(Object[].class))).thenReturn(text);
    final ProxiedLocale locale = mock(ProxiedLocale.class);
    lenient().when(locale.getLocale()).thenReturn("en_us");
    lenient().when(textManager.findRelativeLanguages(any(org.bukkit.command.CommandSender.class))).thenReturn(locale);
    lenient().when(textManager.findRelativeLanguages(any(QUser.class), anyBoolean())).thenReturn(locale);
    lenient().when(plugin.text()).thenReturn(textManager);
    lenient().when(plugin.getTextManager()).thenReturn(textManager);

    // permissions open every door
    final PermissionManager perm = mock(PermissionManager.class);
    lenient().when(perm.hasPermission(any(org.bukkit.command.CommandSender.class), anyString())).thenReturn(true);
    lenient().when(plugin.perm()).thenReturn(perm);

    // manager with the real sendShopInfo body; formatting and interactivity are stubs
    manager = mock(SimpleShopManager.class, org.mockito.Mockito.withSettings().defaultAnswer(CALLS_REAL_METHODS));
    lenient().when(plugin.getShopManager()).thenReturn(manager);
    lenient().when(manager.getInteractiveManager()).thenReturn(mock(ShopManager.InteractiveManager.class));
    // doReturn: with CALLS_REAL_METHODS a when(...) stub would execute the real format
    // once with a null shop argument
    org.mockito.Mockito.doReturn("$1").when(manager).format(anyDouble(), any(Shop.class));
    // Objenesis-style mock: real methods read null fields until injected (same hazard
    // the benchmark fixtures solve with reflective injection)
    injectField(manager, "plugin", plugin);

    // economy affordability never limits the prompt
    final var economyManager = mock(com.ghostchu.quickshop.api.economy.EconomyManager.class);
    final var ecoProvider = mock(com.ghostchu.quickshop.api.economy.EconomyProvider.class);
    lenient().when(economyManager.provider()).thenReturn(ecoProvider);
    lenient().when(ecoProvider.balance(any(QUser.class), anyString())).thenReturn(BigDecimal.valueOf(1_000_000));
    lenient().when(plugin.getEconomyManager()).thenReturn(economyManager);

    final var platform = mock(com.ghostchu.quickshop.platform.Platform.class);
    lenient().when(plugin.platform()).thenReturn(platform);
    lenient().when(platform.setItemStackHoverEvent(any(Component.class), any(ItemStack.class)))
            .thenAnswer(inv->inv.getArgument(0, Component.class));

    world = mock(World.class);
    lenient().when(world.getName()).thenReturn("world");
    player = mock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("entry-trader".getBytes()));
    final var playerInventory = mock(org.bukkit.inventory.PlayerInventory.class);
    lenient().when(playerInventory.getStorageContents()).thenAnswer(inv->new ItemStack[]{mockStack(Material.DIAMOND, 3)});
    lenient().when(playerInventory.getContents()).thenAnswer(inv->new ItemStack[]{mockStack(Material.DIAMOND, 3)});
    lenient().when(playerInventory.getMaxStackSize()).thenReturn(64);
    lenient().when(player.getInventory()).thenReturn(playerInventory);

    shop = mock(Shop.class);
    lenient().when(shop.isUnlimited()).thenReturn(false);
    lenient().when(shop.isFreeShop()).thenReturn(false);
    lenient().when(shop.isStackingShop()).thenReturn(false);
    lenient().when(shop.getPrice()).thenReturn(1.0d);
    lenient().when(shop.bukkitLocation()).thenReturn(new Location(world, 1000, 64, 1000));
    lenient().when(shop.ownerName(any(ProxiedLocale.class))).thenReturn(Component.text("owner"));
    lenient().when(shop.ownerName(anyBoolean(), any(ProxiedLocale.class))).thenReturn(Component.text("owner"));
    lenient().when(shop.getOwner()).thenReturn(mock(QUser.class));
    lenient().when(shop.matches(any(ItemStack.class))).thenReturn(true);
    lenient().when(shop.getItemUnitSize()).thenReturn(1);
    lenient().when(shop.getRuntimeRandomUniqueId()).thenReturn(UUID.randomUUID());
    final ItemStack item = mockStack(Material.DIAMOND, 1);
    lenient().when(shop.getItem()).thenReturn(item);
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  private static void injectField(final Object target, final String name, final Object value) throws Exception {

    Class<?> type = target.getClass();
    while(type != null) {
      try {
        final java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
        return;
      } catch(final NoSuchFieldException e) {
        type = type.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static ItemStack mockStack(final Material material, final int amount) {

    final ItemStack stack = mock(ItemStack.class);
    lenient().when(stack.getType()).thenReturn(material);
    lenient().when(stack.getAmount()).thenReturn(amount);
    lenient().when(stack.getMaxStackSize()).thenReturn(64);
    lenient().when(stack.hasItemMeta()).thenReturn(false);
    lenient().when(stack.clone()).thenReturn(stack);
    return stack;
  }

  @Test
  void sellEntryMeasuresSpaceExactlyTwice() {

    when(shop.isBuying()).thenReturn(true);
    when(shop.getRemainingSpace()).thenReturn(7);

    assertTrue(ShopUtil.sellToShop(player, shop, false, false));

    // one scan for the info panel's space line, one for the out-of-space gate; the cap
    // calculation reuses the gate's measurement (baseline paid four scans)
    verify(shop, times(2)).getRemainingSpace();
    verify(shop, never()).setSignText(any(ProxiedLocale.class));
    verify(shop, times(1)).onClick(player);
  }

  @Test
  void buyEntryMeasuresStockExactlyTwice() {

    when(shop.isBuying()).thenReturn(false);
    when(shop.isSelling()).thenReturn(true);
    when(shop.getRemainingStock()).thenReturn(9);

    assertTrue(ShopUtil.buyFromShop(player, shop, false, false));

    // panel line + out-of-stock gate; the buy-cap calculation reuses the gate value
    verify(shop, times(2)).getRemainingStock();
    verify(shop, never()).setSignText(any(ProxiedLocale.class));
    verify(shop, times(1)).onClick(player);
  }

  @Test
  void sellEntryOutOfSpaceRefusesAfterTwoScans() {

    when(shop.isBuying()).thenReturn(true);
    when(shop.getRemainingSpace()).thenReturn(0);

    assertTrue(ShopUtil.sellToShop(player, shop, false, false));

    // panel scan + gate scan; the refusal path never reaches the cap calculation, so
    // no third measurement (baseline paid the same two here — its extras sat on the
    // prompt path)
    verify(shop, times(2)).getRemainingSpace();
    verify(shop, times(1)).onClick(player);
  }

  @Test
  void listenerKeepsTheExplicitSignRender() {

    final RegisteredListener listener = new RegisteredListener(
            mock(Listener.class), (l, e)->{}, EventPriority.NORMAL, mock(Plugin.class), false);
    AbstractQSEvent.getHandlerList().register(listener);
    try {
      when(shop.isBuying()).thenReturn(true);
      when(shop.getRemainingSpace()).thenReturn(7);

      assertTrue(ShopUtil.sellToShop(player, shop, false, false));

      // with a listener registered the historic render count is preserved: the explicit
      // refresh runs and onClick still renders on its own
      verify(shop, times(1)).setSignText(any(ProxiedLocale.class));
      verify(shop, times(1)).onClick(player);
    } finally {
      HandlerList.unregisterAll();
    }
  }

  @Test
  void freeShopSellCapBoundByPrecomputedSpace() {

    when(shop.isBuying()).thenReturn(true);
    when(shop.isFreeShop()).thenReturn(true);
    when(shop.getRemainingSpace()).thenReturn(4);

    assertTrue(ShopUtil.sellToShop(player, shop, false, false));

    // the how-many prompt for a limited free shop is min(space, player items): the
    // player holds 3 diamonds, the shop admits 4 -> prompt reads 3
    final org.mockito.ArgumentCaptor<Integer> promptItems = org.mockito.ArgumentCaptor.forClass(Integer.class);
    verify(plugin.text(), times(1)).of(any(org.bukkit.command.CommandSender.class),
                                       org.mockito.ArgumentMatchers.eq("how-many-sell"),
                                       promptItems.capture(), org.mockito.ArgumentMatchers.any());
    assertEquals(3, promptItems.getValue());
  }

  @Test
  void infoPanelFetchesItemOncePerPanel() {

    when(shop.isBuying()).thenReturn(true);
    when(shop.getRemainingSpace()).thenReturn(7);

    manager.sendShopInfo(player, shop);

    // every line of the panel reads the one snapshot (baseline fetched it per line)
    verify(shop, times(1)).getItem();
    verify(shop, times(1)).getRemainingSpace();
    verify(shop, times(1)).ownerName(any(ProxiedLocale.class));
  }
}
