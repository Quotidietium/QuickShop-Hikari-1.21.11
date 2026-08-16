package com.ghostchu.quickshop.api.event;

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

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Parent about all events.
 */
public abstract class AbstractQSEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  protected AbstractQSEvent() {

    super(!Bukkit.isPrimaryThread());
  }

  protected AbstractQSEvent(final boolean async) {

    super(async);
  }

  public static HandlerList getHandlerList() {

    return HANDLERS;
  }

  /**
   * Call event on Bukkit event bus and check if cancelled
   *
   * @return Returns true if cancelled, and false if didn't cancel
   */
  public boolean callCancellableEvent() {

    if(hasListeners()) {
      Bukkit.getPluginManager().callEvent(this);
      if(this instanceof final Cancellable cancellable) {

        return cancellable.isCancelled();
      }
    }
    return false;
  }

  /**
   * Dispatches through Bukkit only when at least one listener is registered. Every
   * QuickShop event shares this HandlerList, so hot paths (sign renders, economy
   * commits, data-record saves) fire events constantly; with no listeners the
   * dispatch outcome is provably identical (nothing can cancel or observe the event).
   */
  @Override
  public boolean callEvent() {

    if(hasListeners()) {
      return super.callEvent();
    }
    // nobody listens, so nothing could have cancelled: mirror the uncancelled state
    // (the platform contract returns true when the event may proceed)
    return !(this instanceof final org.bukkit.event.Cancellable cancellable && cancellable.isCancelled());
  }

  /**
   * Whether any listener is registered on the shared QuickShop HandlerList. Hot paths use
   * this to skip building event objects (which may clone payloads) when dispatch is a
   * provable no-op.
   *
   * @return true when at least one listener could observe QuickShop events
   */
  public static boolean hasListeners() {

    return getHandlerList().getRegisteredListeners().length > 0;
  }

  @NotNull
  @Override
  public HandlerList getHandlers() {

    return getHandlerList();
  }

}
