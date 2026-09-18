package com.ghostchu.quickshop.addon.discount;

import com.ghostchu.quickshop.addon.discount.type.CodeType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the atomic per-player quota burn: use() must check-and-increment in one step,
 * otherwise two concurrent buyers of a maxUsage=1 code both pass and both burn it.
 */
class DiscountCodeQuotaTest {

  private DiscountCode code(final int maxUsage) {

    return new DiscountCode(UUID.randomUUID(), "TEST", CodeType.SERVER_ALL_SHOPS,
                            new DiscountCode.PercentageDiscountRate(0.5d), maxUsage, -1d, -1);
  }

  @Test
  void quotaOneExhaustsAfterFirstUse() {

    final DiscountCode code = code(1);
    final UUID player = UUID.randomUUID();

    assertTrue(code.use(player), "first use must burn the single slot");
    assertFalse(code.use(player), "second use must be rejected");
    assertEquals(1, code.getUsages().get(player), "a rejected use must not mutate the counter");
  }

  @Test
  void unlimitedQuotaAlwaysBurns() {

    final DiscountCode code = code(-1);
    final UUID player = UUID.randomUUID();

    for(int i = 0; i < 100; i++) {
      assertTrue(code.use(player));
    }
    assertEquals(100, code.getUsages().get(player));
  }

  @Test
  void concurrentUsesOfSamePlayerCannotOversubscribeQuota() throws Exception {

    // maxUsage is a per-player quota: the same player racing from several region threads
    // (one trade per shop) must still only burn the slot once
    final DiscountCode code = code(1);
    final UUID buyer = UUID.randomUUID();
    final int threads = 8;
    final CountDownLatch ready = new CountDownLatch(threads);
    final CountDownLatch start = new CountDownLatch(1);
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      final List<Future<Boolean>> futures = new ArrayList<>();
      for(int i = 0; i < threads; i++) {
        futures.add(pool.submit(()->{
          ready.countDown();
          start.await();
          return code.use(buyer);
        }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      int burned = 0;
      for(final Future<Boolean> future : futures) {
        if(future.get(5, TimeUnit.SECONDS)) {
          burned++;
        }
      }
      assertEquals(1, burned, "exactly one of the concurrent uses may burn the slot");
      assertEquals(1, code.getUsages().get(buyer));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void separatePlayersOwnIndependentQuotaSlots() {

    final DiscountCode code = code(1);
    final UUID buyerA = UUID.randomUUID();
    final UUID buyerB = UUID.randomUUID();

    assertTrue(code.use(buyerA));
    assertTrue(code.use(buyerB), "another player's slot must be unaffected");
    assertFalse(code.use(buyerA));
    assertFalse(code.use(buyerB));
  }

  @Test
  void applyIsPureAndNeverBurns() {

    final DiscountCode code = code(1);
    final UUID player = UUID.randomUUID();

    assertEquals(50d, code.apply(player, 100d), 0.001d);
    assertEquals(0, code.getUsages().size(), "apply must stay a pure price computation");
    assertTrue(code.use(player));
    assertEquals(100d, code.apply(player, 100d), 0.001d, "apply must stop discounting once the quota is burned");
  }

  @Test
  void saveToStringRoundTripsThroughFromString() {

    final DiscountCode original = code(3);
    final DiscountCode restored = DiscountCode.fromString(original.saveToString());
    assertTrue(restored instanceof DiscountCode, "a 50% code must round-trip");
    assertEquals(original.getCode(), restored.getCode());
    assertEquals(original.getMaxUsage(), restored.getMaxUsage());
  }

  @Test
  void fromStringRejectsFreePurchaseRates() {

    // Gson bypasses constructor validation: a hand-edited data.yml must not yield a 0%/100% code
    final String owner = UUID.randomUUID().toString();
    final String template = "{\"owner\":\"" + owner + "\",\"code\":\"TEST\",\"codeType\":\"SERVER_ALL_SHOPS\",\"rateType\":\"PERCENTAGE\",\"rate\":\"{\\\"percent\\\":%s}\",\"maxUsage\":-1,\"usages\":{},\"shopScope\":[],\"threshold\":-1.0,\"expire\":-1}";
    assertEquals(null, DiscountCode.fromString(template.formatted("0.0")), "a 0% code must be rejected wholesale");
    assertEquals(null, DiscountCode.fromString(template.formatted("1.0")), "a 100% code must be rejected wholesale");
  }
}
