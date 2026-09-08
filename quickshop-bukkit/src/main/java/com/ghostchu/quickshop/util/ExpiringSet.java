package com.ghostchu.quickshop.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A set whose entries expire a fixed lifetime after they were added.
 * <p>
 * Backed by a deadline map: {@link #contains} evaluates the stored deadline, so an
 * expired-but-unswept entry answers false exactly like the previous double mechanism
 * (Guava expireAfterWrite plus the same deadline check). The map cannot grow without
 * bound: {@link #add} sweeps expired deadlines once the map passes a size threshold,
 * amortized O(1) per add. The Guava-cache backing this replaces paid a full LocalCache
 * read-plus-write (with per-entry eviction bookkeeping and a boxed value per add) for
 * every contains/add pair on the click path (player-interact rate limit, trade-menu
 * cooldown).
 */
public class ExpiringSet<T> {

  /** Map-size threshold that triggers an expired-entry sweep on add. */
  private static final int SWEEP_THRESHOLD = 1024;

  private final Map<T, Long> deadlines = new ConcurrentHashMap<>();
  private final long lifetime;

  public ExpiringSet(final long lifetime, final TimeUnit timeUnit) {

    this.lifetime = timeUnit.toMillis(lifetime);
  }

  public void add(final T item) {

    deadlines.put(item, System.currentTimeMillis() + lifetime);
    if(deadlines.size() > SWEEP_THRESHOLD) {
      final long now = System.currentTimeMillis();
      deadlines.values().removeIf(deadline -> deadline <= now);
    }
  }

  public boolean contains(final T item) {

    final Long deadline = deadlines.get(item);
    return deadline != null && deadline > System.currentTimeMillis();
  }

  public void remove(final T item) {

    deadlines.remove(item);
  }

  /**
   * Number of entries whose deadline has not passed yet (the Guava cache reported the
   * same live-only approximation).
   *
   * @return live entry count
   */
  public long size() {

    final long now = System.currentTimeMillis();
    return deadlines.values().stream().filter(deadline -> deadline > now).count();
  }
}
