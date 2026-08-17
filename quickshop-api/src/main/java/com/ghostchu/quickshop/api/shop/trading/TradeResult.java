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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * TradeResult
 *
 * @author creatorfromhell
 * @since 6.3.0.0
 */
public record TradeResult(boolean success, TradeType tradeType, int requestedAmount, int tradedAmount,
                          BigDecimal unitPrice, BigDecimal totalPrice, BigDecimal actorTax,
                          BigDecimal ownerTax, @Nullable TradeFailureReason failureReason,
                          @Nullable String messageKey, @Nullable String debugMessage,
                          @Nullable TradeObservation observation) {

  /**
   * Legacy constructor without inventory observations; equivalent to an empty observation so
   * third-party trade services keep compiling and behaving as before.
   */
  public TradeResult(final boolean success, final TradeType tradeType, final int requestedAmount,
                     final int tradedAmount, final BigDecimal unitPrice, final BigDecimal totalPrice,
                     final BigDecimal actorTax, final BigDecimal ownerTax,
                     @Nullable final TradeFailureReason failureReason, @Nullable final String messageKey,
                     @Nullable final String debugMessage) {

    this(success, tradeType, requestedAmount, tradedAmount, unitPrice, totalPrice, actorTax,
            ownerTax, failureReason, messageKey, debugMessage, TradeObservation.EMPTY);
  }

  /**
   * @return pre-trade inventory measurements taken during validation, never null
   *         (empty when the producing trade service supplies none)
   */
  public @NotNull TradeObservation observation() {

    return observation != null? observation : TradeObservation.EMPTY;
  }
}