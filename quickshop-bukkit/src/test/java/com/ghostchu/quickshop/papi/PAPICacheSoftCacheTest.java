package com.ghostchu.quickshop.papi;

import com.ghostchu.quickshop.QuickShop;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Regression tests for the R56 soft cache: database-backed PAPI handlers must never run
 * their JDBC loader on the placeholder-resolving (usually main) thread. Misses resolve
 * empty and load asynchronously; stale values serve immediately while refreshing.
 */
class PAPICacheSoftCacheTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getLong(anyString(), any(Long.class))).thenReturn(900_000L);
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  @Test
  void missResolvesEmptyAndLoadsOffThread() throws Exception {

    final PAPICache cache = new PAPICache();
    final UUID player = UUID.randomUUID();
    final CountDownLatch loaded = new CountDownLatch(1);
    final Thread[] loaderThread = new Thread[1];

    final Optional<String> first = cache.getCachedSoft(player, "metrics_recent_purchases_global_7", (uuid, parms)->{
      loaderThread[0] = Thread.currentThread();
      loaded.countDown();
      return "42";
    });

    assertFalse(first.isPresent(), "first resolution must not block on the loader");
    assertTrue(loaded.await(5, TimeUnit.SECONDS), "loader must have run asynchronously");
    assertFalse(Thread.currentThread().equals(loaderThread[0]), "loader ran on the caller's thread");

    // the loaded value is served on the next resolution
    final Optional<String> second = cache.getCachedSoft(player, "metrics_recent_purchases_global_7", (uuid, parms)->"changed");
    assertTrue(second.isPresent());
    assertEquals("42", second.get());
  }

  @Test
  void throwingLoaderResolvesEmptyWithoutNegativeCaching() throws Exception {

    final PAPICache cache = new PAPICache();
    final UUID player = UUID.randomUUID();
    final AtomicInteger calls = new AtomicInteger();

    cache.getCachedSoft(player, "failing", (uuid, parms)->{
      calls.incrementAndGet();
      throw new RuntimeException("db down");
    });
    // let the async failure land
    Thread.sleep(200);
    assertFalse(cache.getCachedSoft(player, "failing", (uuid, parms)->{
      calls.incrementAndGet();
      throw new RuntimeException("db down");
    }).isPresent());

    // a subsequent healthy refresh is attempted (not cached as a permanent empty) —
    // poll, since an in-flight failing refresh may briefly defer the healthy attempt
    String healthy = null;
    final long deadline = System.currentTimeMillis() + 5_000;
    while(System.currentTimeMillis() < deadline) {
      cache.getCachedSoft(player, "failing", (uuid, parms)->"ok");
      final Optional<String> resolved = cache.getCachedSoft(player, "failing", (uuid, parms)->"ok");
      if(resolved.isPresent()) {
        healthy = resolved.get();
        break;
      }
      Thread.sleep(50);
    }
    assertEquals("ok", healthy, "healthy refresh must eventually land");
    assertTrue(calls.get() >= 2, "loader re-attempted after failure: " + calls.get());
  }

  @Test
  void freshValueServesWithoutInvokingTheLoaderAgain() {

    final PAPICache cache = new PAPICache();
    final UUID player = UUID.randomUUID();
    final AtomicInteger calls = new AtomicInteger();

    cache.getCachedSoft(player, "stable", (uuid, parms)->{
      calls.incrementAndGet();
      return "v1";
    });
    // wait out the initial async load
    awaitValue(cache, player, "stable");

    final Optional<String> again = cache.getCachedSoft(player, "stable", (uuid, parms)->{
      calls.incrementAndGet();
      return "v2";
    });

    assertEquals("v1", again.orElse(null), "fresh value must serve unchanged");
    assertEquals(1, calls.get(), "no extra loader run inside the TTL window");
  }

  private static void awaitValue(final PAPICache cache, final UUID player, final String args) {

    final long deadline = System.currentTimeMillis() + 5_000;
    while(System.currentTimeMillis() < deadline) {
      if(cache.getCachedSoft(player, args, (uuid, parms)->"nope").isPresent()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch(final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
