package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperIterator;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.trading.TradeObservation;
import com.ghostchu.quickshop.api.shop.trading.TradeOptions;
import com.ghostchu.quickshop.api.shop.trading.TradeResult;
import com.ghostchu.quickshop.api.shop.trading.TradeType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for TradeResult observations: the trade preview measures chest stock /
 * chest space / trader stock / trader space exactly once and reports them on the result, so
 * the shop-manager action layer can consume those values instead of re-scanning the same
 * inventories (message arguments and out-of-stock/out-of-space notifications keep their
 * historical values).
 */
class TradeObservationTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private SimpleTradeService service;
  private QUser trader;
  private Location dropLocation;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    final QuickShop plugin = mock(QuickShop.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    service = new SimpleTradeService(plugin);
    trader = mock(QUser.class);
    lenient().when(trader.getUniqueIdIfRealPlayer()).thenReturn(java.util.Optional.of(
            UUID.nameUUIDFromBytes("obs-trader".getBytes())));
    dropLocation = mock(Location.class);
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
  }

  /** Commit-free options: full preview validation without touching any inventory. */
  private static final TradeOptions NO_COMMIT = TradeOptions.builder().commit(false).updateSigns(false).build();

  private static ItemStack slot(final Material material, final int amount) {

    final ItemStack stack = mock(ItemStack.class);
    lenient().when(stack.getType()).thenReturn(material);
    lenient().when(stack.getAmount()).thenReturn(amount);
    lenient().when(stack.getMaxStackSize()).thenReturn(64);
    lenient().when(stack.hasItemMeta()).thenReturn(false);
    lenient().when(stack.clone()).thenReturn(stack);
    lenient().when(stack.isSimilar(any(ItemStack.class))).thenAnswer(
            inv -> inv.getArgument(0, ItemStack.class).getType() == material);
    return stack;
  }

  /** Wrapper over fixed contents; only countItems/countSpace iteration semantics matter here. */
  private static InventoryWrapper wrapperOf(final ItemStack... contents) {

    final InventoryWrapper wrapper = mock(InventoryWrapper.class);
    lenient().when(wrapper.iterator()).thenAnswer(inv -> iterate(Arrays.asList(contents)));
    return wrapper;
  }

  private static InventoryWrapperIterator iterate(final List<ItemStack> list) {

    final InventoryWrapperIterator it = mock(InventoryWrapperIterator.class);
    final int[] cursor = {0};
    lenient().when(it.hasNext()).thenAnswer(inv -> cursor[0] < list.size());
    lenient().when(it.next()).thenAnswer(inv -> list.get(cursor[0]++));
    return it;
  }

  private Shop sellingShop(final InventoryWrapper chest, final int unitSize) {

    // build the item mock before any in-progress stubbing (creating mocks between
    // when() and thenReturn() trips Mockito's UnfinishedStubbing detection)
    final ItemStack shopItem = slot(Material.DIAMOND, unitSize);
    final Shop shop = mock(Shop.class);
    lenient().when(shop.getItem()).thenReturn(shopItem);
    lenient().when(shop.getItemUnitSize()).thenReturn(unitSize);
    lenient().when(shop.price()).thenReturn(10.0d);
    lenient().when(shop.isValid()).thenReturn(true);
    lenient().when(shop.isFrozen()).thenReturn(false);
    lenient().when(shop.isSelling()).thenReturn(true);
    lenient().when(shop.isBuying()).thenReturn(true);
    lenient().when(shop.isUnlimited()).thenReturn(false);
    lenient().when(shop.getInventory()).thenReturn(chest);
    lenient().when(shop.matches(any(ItemStack.class))).thenAnswer(
            inv -> inv.getArgument(0, ItemStack.class).getType() == Material.DIAMOND);
    return shop;
  }

  @Test
  void buySuccessReportsChestStockAndTraderSpace() {

    // chest: 3 full units of shop item + 2 foreign slots; player: 1 partial diamond + 1 free slot
    final InventoryWrapper chest = wrapperOf(
            slot(Material.DIAMOND, 64), slot(Material.DIAMOND, 64), slot(Material.DIAMOND, 64),
            slot(Material.IRON_INGOT, 7), slot(Material.GOLD_INGOT, 7));
    final InventoryWrapper player = wrapperOf(slot(Material.DIAMOND, 3), null);

    final Shop shop = sellingShop(chest, 64);
    final TradeResult result = service.executeBuyFromShop(shop, trader, player, dropLocation, 1, NO_COMMIT);

    assertTrue(result.success());
    assertNotNull(result.observation());
    assertEquals(3, result.observation().chestStock());
    // space: empty slot 64 + diamond slot (64-3) => 125 items / 64 = 1 unit
    assertEquals(1, result.observation().traderSpace());
    assertNull(result.observation().chestSpace());
    assertNull(result.observation().traderStock());
  }

  @Test
  void stockTooLowReportsMeasuredChestStock() {

    final InventoryWrapper emptyChest = wrapperOf(slot(Material.IRON_INGOT, 7));
    final InventoryWrapper player = wrapperOf();

    final Shop shop = sellingShop(emptyChest, 64);
    final TradeResult result = service.executeBuyFromShop(shop, trader, player, dropLocation, 1, NO_COMMIT);

    assertFalse(result.success());
    assertEquals(0, result.observation().chestStock());
  }

  @Test
  void inventoryFullReportsMeasuredTraderSpace() {

    final InventoryWrapper chest = wrapperOf(slot(Material.DIAMOND, 64));
    final InventoryWrapper fullPlayer = wrapperOf(slot(Material.DIAMOND, 64), slot(Material.IRON_INGOT, 64));

    final Shop shop = sellingShop(chest, 64);
    final TradeResult result = service.executeBuyFromShop(shop, trader, fullPlayer, dropLocation, 1, NO_COMMIT);

    assertFalse(result.success());
    assertEquals(0, result.observation().traderSpace());
    assertEquals(1, result.observation().chestStock());
  }

  @Test
  void sellSuccessReportsChestSpaceAndTraderStock() {

    final InventoryWrapper chest = wrapperOf(slot(Material.DIAMOND, 60), null);
    final InventoryWrapper seller = wrapperOf(slot(Material.DIAMOND, 64), slot(Material.IRON_INGOT, 7));

    final Shop shop = sellingShop(chest, 64);
    lenient().when(shop.getMaxAffordable()).thenReturn(Integer.MAX_VALUE);
    // chest holds 60 diamonds + one empty slot => (4 + 64) / 64 = 1 unit of space
    lenient().when(shop.getRemainingSpace(any())).thenReturn(1);
    final TradeResult result = service.executeSellToShop(shop, trader, seller, dropLocation, 1, NO_COMMIT);

    assertTrue(result.success());
    assertEquals(1, result.observation().traderStock());
    assertEquals(1, result.observation().chestSpace());
    assertNull(result.observation().chestStock());
  }

  @Test
  void itemNotEnoughReportsMeasuredTraderStock() {

    final InventoryWrapper chest = wrapperOf();
    final InventoryWrapper poorSeller = wrapperOf(slot(Material.IRON_INGOT, 7));

    final Shop shop = sellingShop(chest, 64);
    final TradeResult result = service.executeSellToShop(shop, trader, poorSeller, dropLocation, 1, NO_COMMIT);

    assertFalse(result.success());
    assertEquals(0, result.observation().traderStock());
  }

  @Test
  void unlimitedShopSuccessReportsNoChestMeasurements() {

    final InventoryWrapper player = wrapperOf((ItemStack)null);
    final Shop shop = sellingShop(null, 64);
    lenient().when(shop.isUnlimited()).thenReturn(true);

    final TradeResult result = service.executeBuyFromShop(shop, trader, player, dropLocation, 1, NO_COMMIT);

    assertTrue(result.success());
    assertNull(result.observation().chestStock());
  }

  @Test
  void legacyTradeResultConstructorYieldsEmptyObservation() {

    final TradeResult legacy = new TradeResult(
            true, TradeType.BUY_FROM_SHOP,
            1, 1, BigDecimal.TEN, BigDecimal.TEN,
            BigDecimal.ZERO, BigDecimal.ZERO,
            null, null, null);

    assertNotNull(legacy.observation());
    assertNull(legacy.observation().chestStock());
    assertNull(legacy.observation().chestSpace());
    assertNull(legacy.observation().traderStock());
    assertNull(legacy.observation().traderSpace());
    assertEquals(Integer.MAX_VALUE, legacy.observation().chestStockOrMax());
    assertEquals(Integer.MAX_VALUE, legacy.observation().chestSpaceOrMax());
  }

  @Test
  void observationRecordConvenienceAccessors() {

    final var observation = new TradeObservation(7, 3, null, null);
    assertEquals(7, observation.chestStockOrMax());
    assertEquals(3, observation.chestSpaceOrMax());
  }
}
