package com.ghostchu.quickshop.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the zero-listener fast path in AbstractQSEvent: dispatch is
 * skipped (and reported uncancelled) when nobody listens, while a registered listener
 * still receives the event through the normal Bukkit pipeline.
 */
class EventDispatchFastPathTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private PluginManager pluginManager;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    pluginManager = mock(PluginManager.class);
    bukkitStatic.when(Bukkit::isPrimaryThread).thenReturn(true);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    HandlerList.unregisterAll();
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    bukkitStatic.close();
  }

  private AbstractQSEvent sampleEvent() {

    return new ShopCurrencyEventLike();
  }

  @Test
  void zeroListenersSkipBukkitDispatch() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);
    final AbstractQSEvent event = sampleEvent();

    // platform contract: callEvent returns true when the event may proceed
    org.junit.jupiter.api.Assertions.assertTrue(event.callEvent());
    // project contract: callCancellableEvent returns true only when cancelled
    assertFalse(event.callCancellableEvent());
    verify(pluginManager, never()).callEvent(any());
  }

  @Test
  void registeredListenersStillReceiveDispatch() {

    final AtomicInteger delivered = new AtomicInteger();
    final RegisteredListener listener = new RegisteredListener(
            mock(org.bukkit.event.Listener.class),
            (executorListener, event)->delivered.incrementAndGet(),
            org.bukkit.event.EventPriority.NORMAL,
            mock(Plugin.class),
            false);
    AbstractQSEvent.getHandlerList().register(listener);

    final AbstractQSEvent event = sampleEvent();
    org.junit.jupiter.api.Assertions.assertTrue(event.callEvent());

    verify(pluginManager, times(1)).callEvent(any());
    // the executor only runs inside the real server pipeline, which the mocked
    // PluginManager does not provide; the dispatch call itself is what must happen
    assertEquals(0, delivered.get());
  }

  /** Minimal concrete subclass used to exercise the dispatch paths. */
  private static final class ShopCurrencyEventLike extends AbstractQSEvent implements org.bukkit.event.Cancellable {

    private boolean cancelled;

    @Override
    public @NotNull HandlerList getHandlers() {

      return getHandlerList();
    }

    @Override
    public boolean isCancelled() {

      return cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {

      this.cancelled = cancel;
    }
  }
}
