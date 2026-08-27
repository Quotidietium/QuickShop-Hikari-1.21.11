package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.logger.Log;
import com.ghostchu.quickshop.util.performance.PerfMonitor;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * @author Netherfoam
 */
public class ChatListener extends AbstractQSListener {

  private static final String LITEBANS_CANCELLED = "[event cancelled by LiteBans]";

  // hot-path snapshot: every chat message on the server passes this handler, and
  // cancelled ones (mute/filter plugins) walked the config tree for the gate; read on
  // construction and reload instead
  private boolean ignoreCancelChatEvent;

  public ChatListener(final QuickShop plugin) {

    super(plugin);
    init();
  }

  private void init() {

    // no explicit default: mirrors getBoolean(path), where an absent key means false
    this.ignoreCancelChatEvent = plugin.getConfig().getBoolean("shop.ignore-cancel-chat-event");
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onChat(final AsyncPlayerChatEvent e) {

    if(e.isCancelled() && ignoreCancelChatEvent) {
      Log.debug("Ignored a chat event (Cancelled by another plugin, you can force process by turn on ignore-cancel-chat-event)");
      return;
    }

    if(!plugin.getShopManager().getInteractiveManager().containsKey(e.getPlayer().getUniqueId())) {
      return;
    }

    String message = e.getMessage();
    // Support for LiteBans muted players
    if (message.startsWith(LITEBANS_CANCELLED)) {
      message = message.substring(LITEBANS_CANCELLED.length());
    }

    try(PerfMonitor ignored = new PerfMonitor("HandleChat", Duration.of(3, ChronoUnit.SECONDS))) {
      // Fix stupid chat plugin will add a weird space before or after the number we want.
      plugin.getShopManager().handleChat(e.getPlayer(), message.trim());
    }
    e.setCancelled(true);
  }

  /**
   * Callback for reloading
   *
   * @return Reloading success
   */
  @Override
  public ReloadResult reloadModule() {

    init();
    return ReloadResult.builder().status(ReloadStatus.SUCCESS).build();
  }
}
