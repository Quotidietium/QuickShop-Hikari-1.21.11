package com.ghostchu.quickshop.api.shop.trading;

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

import org.jetbrains.annotations.Nullable;

/**
 * Inventory measurements the trade service already took while validating a trade, exposed so
 * callers (stock/space notifications, failure messages) do not have to re-scan the same
 * inventories. All values are pre-trade measurements in shop units; {@code null} means the
 * trade service did not measure that value (for example the chest of an unlimited shop).
 *
 * @author creatorfromhell
 * @since 6.3.0.0
 */
public record TradeObservation(@Nullable Integer chestStock, @Nullable Integer chestSpace,
                               @Nullable Integer traderStock, @Nullable Integer traderSpace) {

  /**
   * Empty observation for trade results constructed without inventory measurements
   * (legacy constructor, third-party trade services).
   */
  public static final TradeObservation EMPTY = new TradeObservation(null, null, null, null);

  /**
   * Convenience accessor mirroring the historical {@code getRemainingStock()} unlimited
   * semantics: measured stock, or {@link Integer#MAX_VALUE} when the chest was not measured.
   *
   * @return measured pre-trade chest stock in shop units, or MAX_VALUE when unknown
   */
  public int chestStockOrMax() {

    return chestStock != null? chestStock : Integer.MAX_VALUE;
  }

  /**
   * Convenience accessor mirroring the historical {@code getRemainingSpace()} unlimited
   * semantics: measured space, or {@link Integer#MAX_VALUE} when the chest was not measured.
   *
   * @return measured pre-trade chest space in shop units, or MAX_VALUE when unknown
   */
  public int chestSpaceOrMax() {

    return chestSpace != null? chestSpace : Integer.MAX_VALUE;
  }
}
