package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.Map;

public class WorldListener extends AbstractQSListener {

  public WorldListener(final QuickShop plugin) {

    super(plugin);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onWorldLoad(final WorldLoadEvent e) {
    /* *************************************
     * This listener fixes any broken world references. Such as hashmap
     * lookups will fail, because the World reference is different, but the
     * world value is the same.
     *  ************************************
     */
    final World world = e.getWorld();
    // snapshot on the event thread; ShopLoader.loadShops does a full-table JDBC fetch and
    // then joins the deserialization futures — running that on the main thread stalls
    // every tick during world loads (startup x3 worlds, runtime via world managers)
    final Chunk[] loadedChunks = world.getLoadedChunks();

    Util.asyncThreadRun(()->{
      plugin.getShopLoader().loadShops(world.getName());
      // Fix any stale World references held by surviving shop Location objects.
      // Done in place on the live per-world map: rebuilding a fresh map and putting it
      // would drop shops that get registered concurrently while we rebuild (and
      // Location hash/equals is world-name based, so mutating the reference is safe).
      for(final Map<Location, Shop> inChunk : plugin.getShopManager().getShops(world.getName()).values()) {
        for(final Shop shop : inChunk.values()) {
          shop.bukkitLocation().setWorld(world);
        }
      }
      // This is a workaround, because I don't get parsed chunk events when a
      // world first loads....
      // So manually tell all of these shops they're loaded. (index/map writes are
      // concurrent; loadShop touches world state and stays on the main thread)
      Util.mainThreadRun(()->{
        for(final Chunk chunk : loadedChunks) {
          final Map<Location, Shop> inChunk = plugin.getShopManager().getShops(chunk);

          if(inChunk == null) {
            continue;
          }

          for(final Shop shop : inChunk.values()) {
            plugin.getShopManager().loadShop(shop);
          }
        }
      });
    });
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onWorldUnload(final WorldUnloadEvent e) {
    // This is a workaround, because I don't get parsed chunk events when a
    // world unloads, I think...
    // So manually tell all of these shops they're unloaded.
    for(final Chunk chunk : e.getWorld().getLoadedChunks()) {
      final Map<Location, Shop> inChunk = plugin.getShopManager().getShops(chunk);
      if(inChunk == null) {
        continue;
      }
      for(final Shop shop : inChunk.values()) {
        if(shop.isLoaded()) { //Don't unload already unloaded shops.
          plugin.getShopManager().unloadShop(shop, true);
        }
      }
    }
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
