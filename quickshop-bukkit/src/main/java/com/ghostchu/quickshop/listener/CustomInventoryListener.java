package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.holder.QuickShopPreviewGUIHolder;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class CustomInventoryListener extends AbstractQSListener {

  public CustomInventoryListener(final QuickShop plugin) {

    super(plugin);
  }

  // no InventoryInteractEvent handler on purpose: InventoryInteractEvent declares no
  // HandlerList of its own, so a handler for it registers on InventoryEvent's shared
  // list — which click/drag events never fire (InventoryClickEvent and
  // InventoryDragEvent each declare their own list and dispatch only there). The
  // shared list fires for FurnaceExtract/Smelt/StartSmelt and the Prepare* family,
  // where Paper's method-handle executor guards with an isInstance check and returns
  // without ever reaching the body. The handler was dead for its intended purpose and
  // one wasted dispatch per furnace/prepare event; the click and drag handlers below
  // carry the whole preview guard.

  @EventHandler(ignoreCancelled = true)
  public void invEvent(final InventoryClickEvent e) {

    if(e.getInventory().getHolder(false) instanceof QuickShopPreviewGUIHolder) {
      e.setCancelled(true);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void invEvent(final InventoryDragEvent e) {

    if(e.getInventory().getHolder(false) instanceof QuickShopPreviewGUIHolder) {
      e.setCancelled(true);
    }
  }

  /**
   * Workaround for TNML's 6-second inventory click blocking after GUI close.
   * TNML adds players to a "recentlyClosed" map when they close a menu GUI,
   * and blocks all inventory clicks for 6 seconds. This is excessive and
   * prevents normal inventory usage after closing a shop GUI.
   * This handler clears the player from that map immediately after close.
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onInventoryClose(final InventoryCloseEvent e) {

    // Remove player from TNML's recentlyClosed map to prevent 6-second click blocking
    MenuManager.instance().recentlyClosed().remove(e.getPlayer().getUniqueId());
  }

  /**
   * Callback for reloading
   *
   * @return Reloading success
   */
  @Override
  public ReloadResult reloadModule() {

    return ReloadResult.builder().status(ReloadStatus.SUCCESS).build();
  }
}
