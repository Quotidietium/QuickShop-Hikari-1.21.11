package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.util.logger.Log;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SignUpdateWatcher implements Runnable {

  //scheduleSignUpdate is called from region threads (hopper events, chunk loads, trades)
  //while run() polls on the async timer thread - a plain LinkedList would corrupt under that
  private final Queue<SignUpdateEntry> signUpdateQueue = new ConcurrentLinkedQueue<>();

  // O(1) companion of the historic queue scan: shops carry identity equality (no
  // equals/hashCode override), so a concurrent key set answers "already scheduled"
  // exactly like walking the queue did - hopper-fed shops schedule on every item move
  // and the queue holds every shop of the current drain window
  private final Set<Shop> pendingShops = ConcurrentHashMap.newKeySet();

  private WrappedTask task = null;

  @Override
  public void run() {

    // async repeating tasks die silently on an uncaught throwable — contain failures so
    // one broken shop/sign cannot stop sign updates server-wide until restart
    try {
      final Instant startTime = Instant.now();
      final Instant endTime = startTime.plusMillis(50);
      SignUpdateEntry entry = signUpdateQueue.poll();
      while(entry != null && !Instant.now().isAfter(endTime)) {
        pendingShops.remove(entry.shop());
        try {
          final Shop shop = entry.shop();
          final ProxiedLocale locale = entry.locale() != null? entry.locale()
                  : QuickShop.getInstance().text().findRelativeLanguages(shop.getOwner(), false);
          shop.setSignText(locale);
        } catch(final Throwable t) {
          QuickShop.getInstance().logger().warn("Failed to update sign for shop {}; entry dropped.", entry.shop().getShopId(), t);
        }
        entry = signUpdateQueue.poll();
      }
    } catch(final Throwable t) {
      QuickShop.getInstance().logger().warn("Sign update watcher cycle failed; task kept alive.", t);
    }
  }

  public void scheduleSignUpdate(@NotNull final Shop shop) {

    schedule(shop, null);
  }

  /**
   * Schedules a sign refresh carrying the locale the update was requested for (trade
   * success renders in the trading player's locale, matching the previous immediate
   * behavior). A null locale falls back to the owner-relative locale.
   *
   * @param shop   the shop whose signs need refreshing
   * @param locale the locale to render with, null for owner-relative
   */
  public void scheduleSignUpdate(@NotNull final Shop shop, @Nullable final ProxiedLocale locale) {

    schedule(shop, locale);
  }

  private void schedule(@NotNull final Shop shop, @Nullable final ProxiedLocale locale) {

    if(!pendingShops.add(shop)) {
      return; // Ignore if schedule too frequently
    }
    signUpdateQueue.add(new SignUpdateEntry(shop, locale));
  }

  public void start(final int i, final int i2) {

    task = QuickShop.folia().getScheduler().runTimerAsync(this, i, i2);
  }

  public void stop() {

    try {
      if(task != null && !task.isCancelled()) {
        task.cancel();
      }
    } catch(final IllegalStateException ex) {
      Log.debug("Task already cancelled " + ex.getMessage());
    }
  }

  private record SignUpdateEntry(@NotNull Shop shop, @Nullable ProxiedLocale locale) {

  }
}
