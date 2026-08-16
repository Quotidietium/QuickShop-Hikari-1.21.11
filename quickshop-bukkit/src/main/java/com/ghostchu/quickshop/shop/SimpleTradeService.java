package com.ghostchu.quickshop.shop;

/*
 * QuickShop-Hikari
 * Copyright (C) 2026 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.trading.TradeFailureReason;
import com.ghostchu.quickshop.api.shop.trading.TradeOptions;
import com.ghostchu.quickshop.api.shop.trading.TradePreview;
import com.ghostchu.quickshop.api.shop.trading.TradeResult;
import com.ghostchu.quickshop.api.shop.trading.TradeService;
import com.ghostchu.quickshop.api.shop.trading.TradeType;
import com.ghostchu.quickshop.util.Util;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * SimpleTradeService
 *
 * @author creatorfromhell
 * @since 6.3.0.0
 */
public class SimpleTradeService implements TradeService {

  private static final BigDecimal ZERO = BigDecimal.ZERO;

  private final QuickShop plugin;

  public SimpleTradeService(@NotNull final QuickShop plugin) {
    this.plugin = plugin;
  }

  @Override
  public @NotNull TradeResult executeBuyFromShop(@NotNull final Shop shop,
                                                 @NotNull final QUser buyer,
                                                 @NotNull final InventoryWrapper buyerInventory,
                                                 @NotNull final Location dropLocation,
                                                 final int amount) {
    return executeBuyFromShop(shop, buyer, buyerInventory, dropLocation, amount, TradeOptions.DEFAULT);
  }

  @Override
  public @NotNull TradeResult executeBuyFromShop(@NotNull final Shop shop,
                                                 @NotNull final QUser buyer,
                                                 @NotNull final InventoryWrapper buyerInventory,
                                                 @NotNull final Location dropLocation,
                                                 final int amount,
                                                 @NotNull final TradeOptions options) {
    Util.ensureThread(false);

    final TradeResult invalid = validateTradeAmount(shop, amount, TradeType.BUY_FROM_SHOP);
    if(invalid != null) {
      return invalid;
    }

    final int normalizedAmount = normalizeAmount(shop, amount);
    if(normalizedAmount < 0) {
      return executeSellToShop(shop, buyer, buyerInventory, dropLocation, -amount, options);
    }

    // one symbol-link resolution per trade: the preview and the commit share the located
    // inventory (single-threaded trade, nothing can swap the container in between)
    final InventoryWrapper locatedChest = shop.isUnlimited()? null : shop.getInventory();
    final TradePreview preview = previewBuyFromShop(shop, buyer, buyerInventory, amount, locatedChest);
    if(!preview.allowed()) {
      return failedResult(
              TradeType.BUY_FROM_SHOP,
              amount,
              preview.allowedAmount(),
              preview.unitPrice(),
              preview.totalPrice(),
              preview.limitingReason(),
              preview.debugMessage());
    }

    try {
      final ItemStack item = shop.getItem();
      final SimpleInventoryTransaction transaction;

      if(shop.isUnlimited()) {
        transaction = SimpleInventoryTransaction.builder()
                .from(null)
                .to(buyerInventory)
                .item(item)
                .amount(normalizedAmount)
                .build();
      } else {
        if(locatedChest == null) {
          return failedResult(
                  TradeType.BUY_FROM_SHOP,
                  amount,
                  0,
                  unitPrice(shop),
                  totalPrice(shop, 0),
                  TradeFailureReason.SHOP_TRANSACTION_FAILED,
                  "Shop inventory is null.");
        }

        transaction = SimpleInventoryTransaction.builder()
                .from(locatedChest)
                .to(buyerInventory)
                .item(item)
                .amount(normalizedAmount)
                .build();
      }

      if(options.commit() && !transaction.failSafeCommit()) {
        if(plugin.getSentryErrorReporter() != null) {
          plugin.getSentryErrorReporter().ignoreThrow();
        }
        return failedResult(
                TradeType.BUY_FROM_SHOP,
                amount,
                0,
                unitPrice(shop),
                totalPrice(shop, 0),
                TradeFailureReason.INVENTORY_TRANSACTION_FAILED,
                "Inventory transaction failed: " + transaction.getLastError());
      }

      if(options.updateSigns() && !shop.isUnlimited()) {
        updateTradeSign(shop, plugin.text().findRelativeLanguages(buyer, false));
      }

      return successResult(
              TradeType.BUY_FROM_SHOP,
              amount,
              amount,
              unitPrice(shop));
    } catch(final Exception e) {
      return failedResult(
              TradeType.BUY_FROM_SHOP,
              amount,
              0,
              unitPrice(shop),
              totalPrice(shop, 0),
              TradeFailureReason.INTERNAL_ERROR,
              e.getMessage());
    }
  }

  @Override
  public @NotNull TradeResult executeSellToShop(@NotNull final Shop shop,
                                                @NotNull final QUser seller,
                                                @NotNull final InventoryWrapper sellerInventory,
                                                @NotNull final Location dropLocation,
                                                final int amount) {
    return executeSellToShop(shop, seller, sellerInventory, dropLocation, amount, TradeOptions.DEFAULT);
  }

  @Override
  public @NotNull TradeResult executeSellToShop(@NotNull final Shop shop,
                                                @NotNull final QUser seller,
                                                @NotNull final InventoryWrapper sellerInventory,
                                                @NotNull final Location dropLocation,
                                                final int amount,
                                                @NotNull final TradeOptions options) {
    Util.ensureThread(false);

    final TradeResult invalid = validateTradeAmount(shop, amount, TradeType.SELL_TO_SHOP);
    if(invalid != null) {
      return invalid;
    }

    final int normalizedAmount = normalizeAmount(shop, amount);
    if(normalizedAmount < 0) {
      return executeBuyFromShop(shop, seller, sellerInventory, dropLocation, -amount, options);
    }

    // one symbol-link resolution per trade, shared by the space check and the commit
    final InventoryWrapper locatedChest = shop.isUnlimited()? null : shop.getInventory();
    final TradePreview preview = previewSellToShop(shop, seller, sellerInventory, amount, locatedChest);
    if(!preview.allowed()) {
      return failedResult(
              TradeType.SELL_TO_SHOP,
              amount,
              preview.allowedAmount(),
              preview.unitPrice(),
              preview.totalPrice(),
              preview.limitingReason(),
              preview.debugMessage());
    }

    try {
      final ItemStack item = shop.getItem();
      final SimpleInventoryTransaction transaction;

      if(shop.isUnlimited()) {
        transaction = SimpleInventoryTransaction.builder()
                .from(sellerInventory)
                .to(null)
                .item(item)
                .amount(normalizedAmount)
                .build();
      } else {
        if(locatedChest == null) {
          return failedResult(
                  TradeType.SELL_TO_SHOP,
                  amount,
                  0,
                  unitPrice(shop),
                  totalPrice(shop, 0),
                  TradeFailureReason.SHOP_TRANSACTION_FAILED,
                  "Shop inventory is null.");
        }

        transaction = SimpleInventoryTransaction.builder()
                .from(sellerInventory)
                .to(locatedChest)
                .item(item)
                .amount(normalizedAmount)
                .build();
      }

      if(options.commit() && !transaction.failSafeCommit()) {
        if(plugin.getSentryErrorReporter() != null) {
          plugin.getSentryErrorReporter().ignoreThrow();
        }
        return failedResult(
                TradeType.SELL_TO_SHOP,
                amount,
                0,
                unitPrice(shop),
                totalPrice(shop, 0),
                TradeFailureReason.INVENTORY_TRANSACTION_FAILED,
                "Inventory transaction failed: " + transaction.getLastError());
      }

      if(options.updateSigns()) {
        updateTradeSign(shop, plugin.text().findRelativeLanguages(seller, false));
      }

      return successResult(
              TradeType.SELL_TO_SHOP,
              amount,
              amount,
              unitPrice(shop));
    } catch(final Exception e) {
      return failedResult(
              TradeType.SELL_TO_SHOP,
              amount,
              0,
              unitPrice(shop),
              totalPrice(shop, 0),
              TradeFailureReason.INTERNAL_ERROR,
              e.getMessage());
    }
  }

  @Override
  public @NotNull TradePreview previewBuyFromShop(@NotNull final Shop shop,
                                                  @NotNull final QUser buyer,
                                                  @NotNull final InventoryWrapper buyerInventory,
                                                  final int amount) {
    return previewBuyFromShop(shop, buyer, buyerInventory, amount, null);
  }

  /**
   * Buy preview over an already-located shop inventory; a null prelocatedChest resolves
   * the symbol link here (public API behaviour). The trade executor passes its single
   * located wrapper so preview and commit share one resolution.
   */
  private @NotNull TradePreview previewBuyFromShop(@NotNull final Shop shop,
                                                   @NotNull final QUser buyer,
                                                   @NotNull final InventoryWrapper buyerInventory,
                                                   final int amount,
                                                   @Nullable final InventoryWrapper prelocatedChest) {
    if(amount <= 0) {
      return previewFailure(TradeType.BUY_FROM_SHOP, amount, TradeFailureReason.INVALID_AMOUNT, "Amount must be > 0.");
    }
    final int previewUnitSize = shop.getItemUnitSize();
    if(previewUnitSize <= 0 || (long)previewUnitSize * amount > Integer.MAX_VALUE) {
      return previewFailure(TradeType.BUY_FROM_SHOP, amount, TradeFailureReason.INVALID_AMOUNT, "Invalid shop item unit size or amount overflow.");
    }
    if(!shop.isValid()) {
      return previewFailure(TradeType.BUY_FROM_SHOP, amount, TradeFailureReason.SHOP_INVALID, "Shop is invalid.");
    }
    if(shop.isFrozen()) {
      return previewFailure(TradeType.BUY_FROM_SHOP, amount, TradeFailureReason.SHOP_FROZEN, "Shop is frozen.");
    }
    if(!shop.isSelling()) {
      return previewFailure(TradeType.BUY_FROM_SHOP, amount, TradeFailureReason.SHOP_DISABLED, "Shop is not selling items.");
    }

    if(!shop.isUnlimited()) {
      final InventoryWrapper chestInv = prelocatedChest != null? prelocatedChest : shop.getInventory();
      if(chestInv == null) {
        return previewFailure(TradeType.BUY_FROM_SHOP, amount, TradeFailureReason.SHOP_TRANSACTION_FAILED, "Shop inventory is null.");
      }

      final int stackSize = Math.max(1, shop.getItemUnitSize());
      final int stock = Util.countItems(chestInv, shop);
      final int requestedUnits = normalizeAmount(shop, amount) / stackSize;
      if(stock < requestedUnits) {
        final int allowedTrades = stock / stackSize;
        return new TradePreview(
                TradeType.BUY_FROM_SHOP,
                amount,
                Math.max(0, allowedTrades),
                unitPrice(shop),
                totalPrice(shop, Math.max(0, allowedTrades)),
                false,
                TradeFailureReason.STOCK_TOO_LOW,
                "Not enough stock in shop.");
      }
    }

    final int buyerSpace = Util.countSpace(buyerInventory, shop);
    if(buyerSpace < amount) {
      return new TradePreview(
              TradeType.BUY_FROM_SHOP,
              amount,
              Math.max(0, buyerSpace),
              unitPrice(shop),
              totalPrice(shop, Math.max(0, buyerSpace)),
              false,
              TradeFailureReason.INVENTORY_FULL,
              "Buyer does not have enough inventory space.");
    }

    return new TradePreview(
            TradeType.BUY_FROM_SHOP,
            amount,
            amount,
            unitPrice(shop),
            totalPrice(shop, amount),
            true,
            null,
            null);
  }

  @Override
  public @NotNull TradePreview previewSellToShop(@NotNull final Shop shop,
                                                 @NotNull final QUser seller,
                                                 @NotNull final InventoryWrapper sellerInventory,
                                                 final int amount) {
    return previewSellToShop(shop, seller, sellerInventory, amount, null);
  }

  /**
   * Sell preview over an already-located shop inventory; a null prelocatedChest resolves
   * the symbol link here (public API behaviour). The trade executor passes its single
   * located wrapper so the space check and the commit share one resolution.
   */
  private @NotNull TradePreview previewSellToShop(@NotNull final Shop shop,
                                                  @NotNull final QUser seller,
                                                  @NotNull final InventoryWrapper sellerInventory,
                                                  final int amount,
                                                  @Nullable final InventoryWrapper prelocatedChest) {
    if(amount <= 0) {
      return previewFailure(TradeType.SELL_TO_SHOP, amount, TradeFailureReason.INVALID_AMOUNT, "Amount must be > 0.");
    }
    final int previewUnitSize = shop.getItemUnitSize();
    if(previewUnitSize <= 0 || (long)previewUnitSize * amount > Integer.MAX_VALUE) {
      return previewFailure(TradeType.SELL_TO_SHOP, amount, TradeFailureReason.INVALID_AMOUNT, "Invalid shop item unit size or amount overflow.");
    }
    if(!shop.isValid()) {
      return previewFailure(TradeType.SELL_TO_SHOP, amount, TradeFailureReason.SHOP_INVALID, "Shop is invalid.");
    }
    if(shop.isFrozen()) {
      return previewFailure(TradeType.SELL_TO_SHOP, amount, TradeFailureReason.SHOP_FROZEN, "Shop is frozen.");
    }
    if(!shop.isBuying()) {
      return previewFailure(TradeType.SELL_TO_SHOP, amount, TradeFailureReason.SHOP_DISABLED, "Shop is not buying items.");
    }


    final int stackSize = Math.max(1, shop.getItemUnitSize());
    final int requestedUnits = normalizeAmount(shop, amount) / stackSize;
    final int sellerStock = Util.countItems(sellerInventory, shop);
    if(sellerStock < requestedUnits) {
      final int allowedTrades = sellerStock / stackSize;
      return new TradePreview(
              TradeType.SELL_TO_SHOP,
              amount,
              Math.max(0, allowedTrades),
              unitPrice(shop),
              totalPrice(shop, Math.max(0, allowedTrades)),
              false,
              TradeFailureReason.ITEM_NOT_ENOUGH,
              "Seller does not have enough items."
      );
    }

    if(!shop.isUnlimited()) {

      //TODO: Make a separate method for util.count items, requested, etc that provide raw output versus normalized output that is divided into stacks.
      final int maxAffordable = shop.getMaxAffordable();
      if(amount > maxAffordable) {
        return new TradePreview(
                TradeType.SELL_TO_SHOP,
                amount,
                Math.max(0, maxAffordable),
                unitPrice(shop),
                totalPrice(shop, Math.max(0, maxAffordable)),
                false,
                TradeFailureReason.INSUFFICIENT_FUNDS,
                "Shop cannot afford that many items."
        );
      }

      int space = shop.getRemainingSpace(prelocatedChest);

      if(space == -1) {
        space = Integer.MAX_VALUE;
      }

      if(requestedUnits > space) {
        return new TradePreview(
                TradeType.SELL_TO_SHOP,
                amount,
                Math.max(0, space),
                unitPrice(shop),
                totalPrice(shop, Math.max(0, space)),
                false,
                TradeFailureReason.SHOP_NO_SPACE,
                "Shop does not have enough space."
        );
      }
    }

    return new TradePreview(
            TradeType.SELL_TO_SHOP,
            amount,
            amount,
            unitPrice(shop),
            totalPrice(shop, amount),
            true,
            null,
            null
    );
  }

  private int normalizeAmount(@NotNull final Shop shop, final int tradeAmount) {
    return shop.getItemUnitSize() * tradeAmount;
  }

  /**
   * Post-trade sign refresh. Immediate rendering costs a full 4-line layout render plus
   * sign block writes per trade; hopper-driven stock changes already batch through
   * SignUpdateWatcher, so trades take the same coalesced path (rapid trading refreshes a
   * shop's signs at most once per watcher cycle, in the trading player's locale as
   * before). Set shop.immediate-trade-sign-updates to restore per-trade rendering.
   */
  private void updateTradeSign(@NotNull final Shop shop, @NotNull final com.ghostchu.quickshop.api.localization.text.ProxiedLocale locale) {

    if(plugin.getConfig().getBoolean("shop.immediate-trade-sign-updates", false)) {
      shop.setSignText(locale);
      return;
    }
    plugin.getSignUpdateWatcher().scheduleSignUpdate(shop, locale);
  }

  /**
   * Validates that a trade request can be safely normalized into an item count. A shop item whose
   * unit size is <= 0, or an amount whose normalized count would overflow int, must never reach
   * the inventory transaction: a zero or overflowed count moves no items while the economy side
   * still transfers the full price, paying out money for nothing.
   *
   * @return a failed TradeResult when the trade must be refused, null when it may proceed
   */
  @Nullable
  private TradeResult validateTradeAmount(@NotNull final Shop shop, final int amount, @NotNull final TradeType type) {

    if(amount == 0) {
      return failedResult(type, amount, 0, unitPrice(shop), totalPrice(shop, 0), TradeFailureReason.INVALID_AMOUNT, "Amount must be > 0.");
    }
    final int unitSize = shop.getItemUnitSize();
    if(unitSize <= 0) {
      return failedResult(type, amount, 0, unitPrice(shop), totalPrice(shop, 0), TradeFailureReason.INVALID_AMOUNT, "Shop item unit size must be > 0.");
    }
    if((long)unitSize * Math.abs((long)amount) > Integer.MAX_VALUE) {
      return failedResult(type, amount, 0, unitPrice(shop), totalPrice(shop, 0), TradeFailureReason.INVALID_AMOUNT, "Trade amount too large (integer overflow).");
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private BigDecimal unitPrice(@NotNull final Shop shop) {

    final Object price = shop.price();
    if(price instanceof final BigDecimal decimal) {
      return decimal;
    }

    if(price instanceof final Double doublePrice) {
      return BigDecimal.valueOf(doublePrice);
    }

    throw new IllegalStateException("DefaultTradeService expects Shop<BigDecimal, ?> pricing.");
  }

  private BigDecimal totalPrice(@NotNull final Shop shop, final int tradeAmount) {
    return unitPrice(shop).multiply(BigDecimal.valueOf(Math.max(0, tradeAmount)));
  }

  private TradePreview previewFailure(@NotNull final TradeType type,
                                      final int requestedAmount,
                                      @NotNull final TradeFailureReason reason,
                                      @NotNull final String debug) {
    return new TradePreview(type, requestedAmount, 0, ZERO, ZERO, false, reason, debug);
  }

  private TradeResult successResult(@NotNull final TradeType type,
                                    final int requestedAmount,
                                    final int tradedAmount,
                                    @NotNull final BigDecimal unitPrice) {
    return new TradeResult(
            true,
            type,
            requestedAmount,
            tradedAmount,
            unitPrice,
            unitPrice.multiply(BigDecimal.valueOf(tradedAmount)),
            ZERO,
            ZERO,
            null,
            null,
            null
    );
  }

  private TradeResult failedResult(@NotNull final TradeType type,
                                   final int requestedAmount,
                                   final int tradedAmount,
                                   @NotNull final BigDecimal unitPrice,
                                   @NotNull final BigDecimal totalPrice,
                                   @NotNull final TradeFailureReason reason,
                                   final String debugMessage) {
    return new TradeResult(
            false,
            type,
            requestedAmount,
            tradedAmount,
            unitPrice,
            totalPrice,
            ZERO,
            ZERO,
            reason,
            null,
            debugMessage
    );
  }
}