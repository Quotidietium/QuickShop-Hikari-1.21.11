package com.ghostchu.quickshop.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the deadline-map ExpiringSet backing: contains/add/remove
 * semantics must match the previous Guava-cache form (deadline-based expiry, add
 * overwrites the deadline, remove drops the entry), and the add-time sweep must keep
 * the internal map bounded so short-lifetime sets (click rate limits) cannot retain
 * stale keys.
 */
class ExpiringSetTest {

  @Test
  void entriesExpireAfterTheirLifetime() throws Exception {

    final ExpiringSet<String> set = new ExpiringSet<>(40, TimeUnit.MILLISECONDS);
    assertFalse(set.contains("player"));

    set.add("player");
    assertTrue(set.contains("player"));

    Thread.sleep(80);
    assertFalse(set.contains("player"), "entry must expire after the lifetime");
    // the expired key may linger unswept, but contains must still answer false
    assertEquals(0, set.size(), "size counts only live entries");
  }

  @Test
  void removeDropsTheEntry() {

    final ExpiringSet<String> set = new ExpiringSet<>(1, TimeUnit.HOURS);
    set.add("player");
    assertTrue(set.contains("player"));
    set.remove("player");
    assertFalse(set.contains("player"));
    assertEquals(0, set.size());
  }

  @Test
  void reAddRefreshesTheDeadline() throws Exception {

    final ExpiringSet<String> set = new ExpiringSet<>(60, TimeUnit.MILLISECONDS);
    set.add("player");
    Thread.sleep(40);
    set.add("player");
    Thread.sleep(30);
    // 70ms after the first add, 30ms after the second: only the refreshed deadline counts
    assertTrue(set.contains("player"));
  }

  @Test
  void sweepKeepsTheMapBoundedAfterMassExpiry() throws Exception {

    final ExpiringSet<UUID> set = new ExpiringSet<>(1, TimeUnit.MILLISECONDS);
    for(int i = 0; i < 1500; i++) {
      set.add(UUID.nameUUIDFromBytes(new byte[]{(byte)i, (byte)(i >> 8)}));
    }
    Thread.sleep(20);
    assertFalse(set.contains(UUID.nameUUIDFromBytes(new byte[]{1, 0})));

    // one more add past the threshold sweeps the expired deadlines out of the map
    final UUID live = UUID.nameUUIDFromBytes(new byte[]{9, 9});
    set.add(live);
    assertTrue(set.contains(live));
    final Map<?, ?> internal = deadlines(set);
    assertTrue(internal.size() <= 1100,
               "the map must stay bounded around the sweep threshold after mass expiry, "
               + "internal size was " + internal.size());
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> deadlines(final ExpiringSet<?> set) throws Exception {

    final Field field = ExpiringSet.class.getDeclaredField("deadlines");
    field.setAccessible(true);
    return (Map<Object, Object>)field.get(set);
  }
}
