package com.ghostchu.quickshop.api.economy;
/*
 * QuickShop-Hikari
 * Copyright (C) 2025 Daniel "creatorfromhell" Vidmar
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

import com.ghostchu.quickshop.api.obj.QUser;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * EconomyProvider
 *
 * @author creatorfromhell
 * @since 6.2.0.11
 */
public interface EconomyProvider {

  String ERROR_MESSAGE = "QuickShop received an error when processing Economy response, THIS NOT A QUICKSHOP FAULT, you might need ask help with your Economy Provider plugin (%s) author.";

  /**
   * Retrieves the name associated with this EconomyProvider.
   *
   * @return The name of the EconomyProvider
   */
  @NotNull
  String name();

  /**
   * Retrieves the name associated with this EconomyProvider.
   *
   * @return The name of the EconomyProvider
   */
  String providerName();

  /**
   * Retrieves the last error that occurred within the EconomyProvider.
   *
   * @return The last error message as a String, or an empty String if no error has occurred.
   */
  @NotNull
  String lastError();

  /**
   * Checks if the instance of EconomyProvider is valid.
   *
   * @return true if the EconomyProvider instance is valid, false otherwise
   */
  boolean valid();

  /**
   * Formats the given amount in the specified world.
   *
   * @param amount the amount to format
   * @param world  the world in which the balance exists
   *
   * @return the formatted amount as a String
   */
  @NotNull
  String format(final @NotNull BigDecimal amount, final @NotNull String world);

  /**
   * Calculate the balance of a specific user in the given world.
   *
   * @param user  the user for which to calculate the balance
   * @param world the world in which the user's balance exists
   *
   * @return the balance of the user
   */
  @NotNull
  BigDecimal balance(final @NotNull QUser user, final @NotNull String world);

  /**
   * Deposits the specified amount into the user's account in the specified world.
   *
   * @param user   the user whose account will receive the deposit
   * @param world  the world in which the user's account exists
   * @param amount the amount to be deposited into the user's account
   *
   * @return true if the deposit was successful, false otherwise
   */
  boolean deposit(final @NotNull QUser user, final @NotNull String world, final @NotNull BigDecimal amount);

  /**
   * Transfers the specified amount from one user to another user in the given world.
   *
   * @param from   the user from whose account the amount will be transferred
   * @param to     the user to whose account the amount will be transferred
   * @param world  the world in which the users' accounts exist
   * @param amount the amount to be transferred from one user to another
   *
   * @return true if the transfer was successful, false otherwise
   */
  default boolean transfer(final @NotNull QUser from, final @NotNull QUser to, final @NotNull String world, final @NotNull BigDecimal amount) {

    if(!valid()) {
      return false;
    }

    if(this.balance(from, world).compareTo(amount) >= 0) {

      if(this.withdraw(from, world, amount)) {

        if(this.deposit(to, world, amount)) {

          this.deposit(from, world, amount);
          return true; //TODO: This was false before which I believe was a bug, but need to test to confirm.
        }
        return false;
      }
      return false;
    }
    return false;
  }

  /**
   * Withdraws the specified amount from the user's account in the given world.
   *
   * @param user   the user from whose account the amount will be withdrawn
   * @param world  the world in which the user's account exists
   * @param amount the amount to be withdrawn from the user's account
   *
   * @return true if the withdrawal was successful, false otherwise
   */
  boolean withdraw(final @NotNull QUser user, final @NotNull String world, final @NotNull BigDecimal amount);
}
