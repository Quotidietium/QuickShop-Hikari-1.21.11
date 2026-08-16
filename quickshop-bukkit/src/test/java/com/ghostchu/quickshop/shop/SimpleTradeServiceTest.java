package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.trading.TradeFailureReason;
import com.ghostchu.quickshop.api.shop.trading.TradeOptions;
import com.ghostchu.quickshop.api.shop.trading.TradePreview;
import com.ghostchu.quickshop.api.shop.trading.TradeResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the zero-unit / integer-overflow trade-amount guards
 * (money must never move when the item count normalizes to zero).
 */
class SimpleTradeServiceTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private SimpleTradeService service;
  private QUser trader;
  private InventoryWrapper traderInventory;
  private Location dropLocation;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    final QuickShop plugin = mock(QuickShop.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    service = new SimpleTradeService(plugin);
    trader = mock(QUser.class);
    traderInventory = mock(InventoryWrapper.class);
    dropLocation = mock(Location.class);
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
  }

  private Shop unlimitedShopWithUnitSize(final int unitSize, final boolean frozen) {

    final ItemStack shopItem = mock(ItemStack.class);
    when(shopItem.getAmount()).thenReturn(Math.max(0, unitSize));
    when(shopItem.getType()).thenReturn(Material.DIRT);

    final Shop shop = mock(Shop.class);
    when(shop.getItem()).thenReturn(shopItem);
    when(shop.getItemUnitSize()).thenReturn(Math.max(0, unitSize));
    when(shop.price()).thenReturn(10.0d);
    when(shop.isValid()).thenReturn(true);
    when(shop.isFrozen()).thenReturn(frozen);
    when(shop.isUnlimited()).thenReturn(true);
    when(shop.isSelling()).thenReturn(true);
    return shop;
  }

  @Test
  void zeroUnitSizeShopIsRefused() {

    final Shop shop = unlimitedShopWithUnitSize(0, false);
    final TradePreview preview = service.previewBuyFromShop(shop, trader, traderInventory, 5);

    assertFalse(preview.allowed());
    assertEquals(TradeFailureReason.INVALID_AMOUNT, preview.limitingReason());
  }

  @Test
  void overflowAmountIsRefused() {

    //64 * (1 << 26) overflows int to 0 - the classic money-for-nothing input
    final Shop shop = unlimitedShopWithUnitSize(64, false);
    final TradePreview preview = service.previewBuyFromShop(shop, trader, traderInventory, 1 << 26);

    assertFalse(preview.allowed());
    assertEquals(TradeFailureReason.INVALID_AMOUNT, preview.limitingReason());
  }

  @Test
  void validAmountsPassTheUnitGuards() {

    //a frozen shop fails later with SHOP_FROZEN, which proves the unit-size/overflow guards passed
    final Shop shop = unlimitedShopWithUnitSize(64, true);
    final TradePreview preview = service.previewBuyFromShop(shop, trader, traderInventory, 10);

    assertFalse(preview.allowed());
    assertEquals(TradeFailureReason.SHOP_FROZEN, preview.limitingReason());
  }

  @Test
  void executeRefusesZeroUnitSizeBeforeTouchingInventories() {

    final Shop shop = unlimitedShopWithUnitSize(0, false);
    final TradeResult result = service.executeBuyFromShop(shop, trader, traderInventory, dropLocation, 5, TradeOptions.DEFAULT);

    assertFalse(result.success());
    assertEquals(TradeFailureReason.INVALID_AMOUNT, result.failureReason());
  }

  @Test
  void executeRefusesOverflowAmountBeforeTouchingInventories() {

    final Shop shop = unlimitedShopWithUnitSize(64, false);
    final TradeResult result = service.executeSellToShop(shop, trader, traderInventory, dropLocation, 1 << 26, TradeOptions.DEFAULT);

    assertFalse(result.success());
    assertEquals(TradeFailureReason.INVALID_AMOUNT, result.failureReason());
  }

  @Test
  void executeRefusesZeroAmount() {

    final Shop shop = unlimitedShopWithUnitSize(64, false);
    final TradeResult result = service.executeBuyFromShop(shop, trader, traderInventory, dropLocation, 0, TradeOptions.DEFAULT);

    assertFalse(result.success());
    assertEquals(TradeFailureReason.INVALID_AMOUNT, result.failureReason());
  }
}
