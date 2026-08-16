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
import java.util.concurrent.ConcurrentLinkedQueue;

public class SignUpdateWatcher implements Runnable {

  //scheduleSignUpdate is called from region threads (hopper events, chunk loads, trades)
  //while run() polls on the async timer thread - a plain LinkedList would corrupt under that
  private final Queue<SignUpdateEntry> signUpdateQueue = new ConcurrentLinkedQueue<>();

  private WrappedTask task = null;

  @Override
  public void run() {

    final Instant startTime = Instant.now();
    final Instant endTime = startTime.plusMillis(50);
    SignUpdateEntry entry = signUpdateQueue.poll();
    while(entry != null && !Instant.now().isAfter(endTime)) {
      final Shop shop = entry.shop();
      final ProxiedLocale locale = entry.locale() != null? entry.locale()
              : QuickShop.getInstance().text().findRelativeLanguages(shop.getOwner(), false);
      shop.setSignText(locale);
      entry = signUpdateQueue.poll();
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

    for(final SignUpdateEntry entry : signUpdateQueue) {
      if(entry.shop().equals(shop)) {
        return; // Ignore if schedule too frequently
      }
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
