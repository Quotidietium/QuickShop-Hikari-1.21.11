package com.ghostchu.quickshop.obj;

import com.ghostchu.quickshop.common.util.QuickExecutor;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R59: after a hot disable/enable cycle the static QuickExecutor pools are shut down and
 * rebuilt, but cached QUserImpl instances keep holding the dead executor — profile
 * lookups then failed with RejectedExecutionException for up to the cache TTL. A user
 * constructed with (or cached with) a shut-down executor must hand a live pool to the
 * finder at use time.
 */
class QUserImplExecutorHealTest {

  @Test
  void shutdownExecutorHealsToLivePoolAtUseTime() {

    final var finder = mock(com.ghostchu.quickshop.api.shop.PlayerFinder.class);
    final UUID id = UUID.nameUUIDFromBytes(new byte[]{7});
    final AtomicReference<ExecutorService> seen = new AtomicReference<>();
    // the uuid -> name lookup runs during construction (parseFromUUID)
    when(finder.uuid2NameFuture(eq(id), eq(true), any(ExecutorService.class)))
            .thenAnswer(inv->{
              seen.set(inv.getArgument(2));
              return CompletableFuture.completedFuture("healed-name");
            });

    final ExecutorService dead = Executors.newSingleThreadExecutor();
    dead.shutdownNow();

    QUserImpl.createSync(finder, id, dead);

    assertNotSame(dead, seen.get(), "a shut-down executor must be swapped for a live pool");
    assertFalse(seen.get().isShutdown());
    assertEquals(QuickExecutor.getPrimaryProfileIoExecutor(), seen.get());
  }
}
