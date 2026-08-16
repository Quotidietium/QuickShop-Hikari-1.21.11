package com.ghostchu.quickshop.obj;

import com.ghostchu.quickshop.api.shop.PlayerFinder;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for the QUserImpl deserialize intern cache: repeated strings reuse
 * the parsed instance and skip redundant finder lookups.
 */
class QUserInternTest {

  @Test
  void repeatedDeserializationsShareTheInstance() {

    final PlayerFinder finder = mock(PlayerFinder.class);
    lenient().when(finder.uuid2NameFuture(any(UUID.class), anyBoolean(), any()))
            .thenReturn(CompletableFuture.completedFuture("owner"));
    final var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      final UUID uuid = UUID.randomUUID();
      final String serialized = uuid.toString();
      final QUserImpl first = QUserImpl.deserialize(finder, serialized, executor);
      final QUserImpl second = QUserImpl.deserialize(finder, serialized, executor);
      assertSame(first, second, "identical serialized forms must reuse the interned instance");
      assertEquals(uuid, first.getUniqueId());
      // the async name lookup fires once for the parse, never again for cache hits
      verify(finder, times(1)).uuid2NameFuture(any(UUID.class), anyBoolean(), any());
    } finally {
      executor.shutdownNow();
    }
  }
}
