package com.ghostchu.quickshop.menu.shared;
/*
 * QuickShop-Hikari
 * Copyright (C) 2024 Daniel "creatorfromhell" Vidmar
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
import com.ghostchu.quickshop.util.logger.Log;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Manages chat input for GUI interactions. This properly cancels chat events so messages don't
 * appear in public chat. Also handles re-opening the menu after input is processed.
 *
 * @author creatorfromhell
 * @since 6.2.0.11
 */
public class GuiChatInputManager implements Listener {

  // volatile: onChat runs on the async chat thread while the field is written from the
  // main/region thread on first menu use — without it the chat thread may observe null
  // and build a second manager whose pendingInputs is forever empty
  private static volatile GuiChatInputManager instance;

  // a prompt older than this is dropped instead of consumed: pending inputs must not
  // wait forever for a message they would swallow (and for confirm prompts, execute)
  private static final long INPUT_TTL_MS = 60_000L;

  private final Map<UUID, ChatInputContext> pendingInputs = new ConcurrentHashMap<>();
  private final QuickShop plugin;
  // same snapshot contract as ChatListener: the gate is read once per instance, not per
  // message (every chat line on the server passes onChat)
  private final boolean ignoreCancelChatEvent;
  private boolean registered = false;

  private GuiChatInputManager(@NotNull final QuickShop plugin) {

    this.plugin = plugin;
    this.ignoreCancelChatEvent = plugin.getConfig().getBoolean("shop.ignore-cancel-chat-event");
  }

  /**
   * Gets or creates the singleton instance.
   */
  @NotNull
  public static GuiChatInputManager getInstance() {

    // a full disable/enable cycle (PlugMan, /reload) unregisters our listener with the
    // old plugin but leaves `registered == true` on this static singleton — without the
    // instance check the "new" manager would never re-register (chat input dead) and the
    // old plugin's whole object graph stays pinned by the static field
    if(instance == null || instance.plugin != QuickShop.getInstance()) {
      instance = new GuiChatInputManager(QuickShop.getInstance());
    }
    return instance;
  }

  /**
   * Registers a chat input handler for a player with menu context for re-opening. The handler will
   * be called when the player sends a chat message.
   *
   * @param player   The player to listen for
   * @param handler  The handler function. Returns true if input is accepted (stop listening), false
   *                 to keep waiting for more input.
   * @param prompt   The prompt message to send to the player
   * @param menuName The menu name to re-open after input (e.g., "qs:keeper")
   * @param menuPage The menu page to re-open
   */
  public void requestInput(@NotNull final Player player,
                           @NotNull final Function<String, Boolean> handler,
                           @Nullable final String prompt,
                           @Nullable final String menuName,
                           final int menuPage) {

    ensureRegistered();

    pendingInputs.put(player.getUniqueId(), new ChatInputContext(handler, menuName, menuPage, System.currentTimeMillis()));

    if(prompt != null && !prompt.isEmpty()) {
      player.sendMessage(prompt);
    }

    Log.debug("GuiChatInputManager: Registered input handler for player " + player.getName() +
              " (menu: " + menuName + ", page: " + menuPage + ")");
  }

  /**
   * Registers a chat input handler for a player without menu context. The menu will not be
   * re-opened after input.
   *
   * @param player  The player to listen for
   * @param handler The handler function. Returns true if input is accepted (stop listening), false
   *                to keep waiting for more input.
   * @param prompt  The prompt message to send to the player
   */
  public void requestInput(@NotNull final Player player,
                           @NotNull final Function<String, Boolean> handler,
                           @Nullable final String prompt) {

    requestInput(player, handler, prompt, null, 1);
  }

  /**
   * Cancels any pending input request for a player and optionally re-opens the menu.
   */
  public void cancelInput(@NotNull final UUID playerId, final boolean reopenMenu) {

    final ChatInputContext context = pendingInputs.remove(playerId);
    Log.debug("GuiChatInputManager: Cancelled input handler for player " + playerId);

    if(reopenMenu && context != null && context.menuName() != null) {
      reopenMenu(playerId, context);
    }
  }

  /**
   * Cancels any pending input request for a player without re-opening menu.
   */
  public void cancelInput(@NotNull final UUID playerId) {

    cancelInput(playerId, false);
  }

  /**
   * Checks if a player has a pending input request.
   */
  public boolean hasPendingInput(@NotNull final UUID playerId) {

    return pendingInputs.containsKey(playerId);
  }

  /*
   * HIGHEST with the same config gate as ChatListener: mute/filter plugins cancel at
   * NORMAL or above, so a LOWEST handler never saw a cancellation and menu inputs
   * (price/amount/search/delete-confirm) worked for muted players regardless of
   * shop.ignore-cancel-chat-event. Default false keeps that behavior (prompts are not
   * public chat); setting it true makes the gate effective for menu input too.
   */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void onChat(final AsyncChatEvent event) {

    if(event.isCancelled() && ignoreCancelChatEvent) {
      return;
    }

    final UUID playerId = event.getPlayer().getUniqueId();
    // atomically claim the pending input: get() plus a remove deferred to the next tick
    // let two same-tick messages both observe the context and run the handler twice
    // (double price set, double delete attempt). The loser sees null and falls through.
    final ChatInputContext context = pendingInputs.remove(playerId);

    if(context == null) {
      return;
    }

    // an expired prompt must neither swallow the message nor run its handler - drop
    // silently and let the chat through (the handler stays removed)
    if(System.currentTimeMillis() - context.createdAt() > INPUT_TTL_MS) {
      Log.debug("GuiChatInputManager: Dropped expired input from " + event.getPlayer().getName());
      return;
    }

    // Cancel the event so it doesn't show in chat
    event.setCancelled(true);

    String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
    // the raw string is client-controlled: legacy section codes and control
    // characters must not feed handlers (names, searches) or viewer data
    message = message.replaceAll("(?i)§[0-9a-fk-or]", "").replaceAll("\\p{Cntrl}", "").trim();
    final String input = message;
    final Player eventPlayer = event.getPlayer();
    Log.debug("GuiChatInputManager: Received input from " + eventPlayer.getName() + ": " + message);

    // Process on the player's region thread for Folia compatibility
    QuickShop.folia().getScheduler().runAtEntityLater(eventPlayer, ()->{
      try {
        final boolean accepted = context.handler().apply(input);
        if(accepted) {
          Log.debug("GuiChatInputManager: Input accepted, removed handler for " + playerId);

          // Re-open the menu if configured
          if(context.menuName() != null) {
            reopenMenu(playerId, context);
          }
        } else {
          // the handler wants more input — re-arm only if no new request took the slot
          // while this task was pending (a newer prompt must win over the stale one);
          // the fresh timestamp restarts the expiry budget for the follow-up message
          pendingInputs.putIfAbsent(playerId, new ChatInputContext(context.handler(), context.menuName(), context.menuPage(), System.currentTimeMillis()));
        }
      } catch(final Exception e) {
        plugin.logger().warn("Error processing GUI chat input for player " + playerId, e);

        // Re-open menu even on error; the input stays consumed (a throwing handler
        // must not be re-armed against the next unrelated message)
        if(context.menuName() != null) {
          reopenMenu(playerId, context);
        }
      }
    }, 1);
  }

  /**
   * Re-opens the menu for a player after chat input is processed.
   */
  private void reopenMenu(@NotNull final UUID playerId, @NotNull final ChatInputContext context) {

    final Player player = Bukkit.getPlayer(playerId);
    if(player == null || !player.isOnline()) {
      return;
    }

    Log.debug("GuiChatInputManager: Re-opening menu " + context.menuName() +
              " page " + context.menuPage() + " for player " + player.getName());

    // Small delay on player's region thread to ensure everything is cleaned up before re-opening
    QuickShop.folia().getScheduler().runAtEntityLater(player, ()->{
      final Player onlinePlayer = Bukkit.getPlayer(playerId);
      if(onlinePlayer == null || !onlinePlayer.isOnline()) {
        return;
      }
      final MenuPlayer menuPlayer = QuickShop.getInstance().createMenuPlayer(onlinePlayer);
      menuPlayer.inventory().openMenu(menuPlayer, context.menuName(), context.menuPage());
    }, 2);
  }

  @EventHandler
  public void onPlayerQuit(final PlayerQuitEvent event) {

    pendingInputs.remove(event.getPlayer().getUniqueId());
  }

  private void ensureRegistered() {

    if(!registered) {
      Bukkit.getPluginManager().registerEvents(this, plugin.getJavaPlugin());
      registered = true;
      Log.debug("GuiChatInputManager: Registered event listener");
    }
  }

  /**
   * Unregisters the listener (call on plugin disable).
   */
  public void shutdown() {

    if(registered) {
      HandlerList.unregisterAll(this);
      registered = false;
    }
    pendingInputs.clear();
  }

  /**
   * Record to hold the chat input context including handler and menu info.
   */
  private record ChatInputContext(
          Function<String, Boolean> handler,
          @Nullable String menuName,
          int menuPage,
          long createdAt
  ) { }
}
