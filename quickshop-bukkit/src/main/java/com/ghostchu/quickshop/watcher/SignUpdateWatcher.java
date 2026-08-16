package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.util.logger.Log;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SignUpdateWatcher implements Runnable {

  //scheduleSignUpdate is called from region threads (hopper events, chunk loads) while
  //run() polls on the async timer thread - a plain LinkedList would corrupt under that
  private final Queue<Shop> signUpdateQueue = new ConcurrentLinkedQueue<>();

  private WrappedTask task = null;

  @Override
  public void run() {

    final Instant startTime = Instant.now();
    final Instant endTime = startTime.plusMillis(50);
    Shop shop = signUpdateQueue.poll();
    while(shop != null && !Instant.now().isAfter(endTime)) {
      shop.setSignText(QuickShop.getInstance().text().findRelativeLanguages(shop.getOwner(), false));
      shop = signUpdateQueue.poll();
    }
  }

  public void scheduleSignUpdate(@NotNull final Shop shop) {

    if(signUpdateQueue.contains(shop)) {
      return; // Ignore if schedule too frequently
    }
    signUpdateQueue.add(shop);
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
}
