package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperIterator;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.trading.TradeFailureReason;
import com.ghostchu.quickshop.api.shop.trading.TradePreview;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the failed-preview allowedAmount: Util.countItems already
 * reports whole trade units, and the previews used to divide that unit count by the
 * stacking unit size a second time — a 16-per-trade shop with 35 items in stock (2
 * possible trades) reported an allowed amount of 0.
 */
class TradePreviewAllowedAmountTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private SimpleTradeService service;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    final QuickShop plugin = mock(QuickShop.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    service = new SimpleTradeService(plugin);
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
  }

  private ItemStack unitStack(final int unitSize) {

    final ItemStack stack = mock(ItemStack.class);
    when(stack.getType()).thenReturn(Material.DIRT);
    when(stack.getAmount()).thenReturn(unitSize);
    lenient().when(stack.getMaxStackSize()).thenReturn(64);
    return stack;
  }

  private InventoryWrapper inventoryWithUnits(final int itemCount) {

    final ItemStack stack = mock(ItemStack.class);
    when(stack.getType()).thenReturn(Material.DIRT);
    when(stack.getAmount()).thenReturn(itemCount);

    final InventoryWrapperIterator iterator = mock(InventoryWrapperIterator.class);
    when(iterator.hasNext()).thenReturn(true, false);
    when(iterator.next()).thenReturn(stack);

    final InventoryWrapper wrapper = mock(InventoryWrapper.class);
    when(wrapper.iterator()).thenReturn(iterator);
    return wrapper;
  }

  @Test
  void buyPreviewAllowedAmountIsTradeUnitsNotItems() {

    final InventoryWrapper chest = inventoryWithUnits(35);

    final Shop shop = mock(Shop.class);
    when(shop.isUnlimited()).thenReturn(false);
    when(shop.isValid()).thenReturn(true);
    when(shop.isFrozen()).thenReturn(false);
    when(shop.isSelling()).thenReturn(true);
    when(shop.getInventory()).thenReturn(chest);
    when(shop.matches(any(ItemStack.class))).thenReturn(true);
    // default methods on the Shop mock execute their real body until stubbed, so the
    // unit-size default (getItem().getAmount()) needs getItem() answered first
    final ItemStack unit = unitStack(16);
    when(shop.getItem()).thenReturn(unit);
    when(shop.getItemUnitSize()).thenReturn(16);
    when(shop.price()).thenReturn(10.0d);

    // 35 items / 16 per trade = 2 possible trades; requesting 5 must report 2, not 0
    final TradePreview preview = service.previewBuyFromShop(shop, mock(com.ghostchu.quickshop.api.obj.QUser.class), mock(InventoryWrapper.class), 5);

    assertFalse(preview.allowed());
    assertEquals(TradeFailureReason.STOCK_TOO_LOW, preview.limitingReason());
    assertEquals(2, preview.allowedAmount(), "allowedAmount must be trade units (countItems already divided)");
  }

  @Test
  void sellPreviewAllowedAmountIsTradeUnitsNotItems() {

    final InventoryWrapper sellerInventory = inventoryWithUnits(35);

    final Shop shop = mock(Shop.class);
    when(shop.isUnlimited()).thenReturn(false);
    when(shop.isValid()).thenReturn(true);
    when(shop.isFrozen()).thenReturn(false);
    when(shop.isBuying()).thenReturn(true);
    when(shop.matches(any(ItemStack.class))).thenReturn(true);
    final ItemStack unit = unitStack(16);
    when(shop.getItem()).thenReturn(unit);
    when(shop.getItemUnitSize()).thenReturn(16);
    when(shop.price()).thenReturn(10.0d);

    final TradePreview preview = service.previewSellToShop(shop, mock(com.ghostchu.quickshop.api.obj.QUser.class), sellerInventory, 5);

    assertFalse(preview.allowed());
    assertEquals(TradeFailureReason.ITEM_NOT_ENOUGH, preview.limitingReason());
    assertEquals(2, preview.allowedAmount(), "allowedAmount must be trade units (countItems already divided)");
  }

  @Test
  void singleItemShopsKeepTheirExactAllowedAmount() {

    final InventoryWrapper chest = inventoryWithUnits(7);

    final Shop shop = mock(Shop.class);
    when(shop.isUnlimited()).thenReturn(false);
    when(shop.isValid()).thenReturn(true);
    when(shop.isFrozen()).thenReturn(false);
    when(shop.isSelling()).thenReturn(true);
    when(shop.getInventory()).thenReturn(chest);
    when(shop.matches(any(ItemStack.class))).thenReturn(true);
    final ItemStack unit = unitStack(1);
    when(shop.getItem()).thenReturn(unit);
    when(shop.getItemUnitSize()).thenReturn(1);
    when(shop.price()).thenReturn(10.0d);

    final TradePreview preview = service.previewBuyFromShop(shop, mock(com.ghostchu.quickshop.api.obj.QUser.class), mock(InventoryWrapper.class), 10);

    assertFalse(preview.allowed());
    assertEquals(7, preview.allowedAmount());
  }
}
