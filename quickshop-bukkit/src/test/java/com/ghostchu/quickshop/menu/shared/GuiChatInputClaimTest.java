package com.ghostchu.quickshop.menu.shared;

import com.ghostchu.quickshop.QuickShop;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R59: the old get()-then-delayed-remove flow let two same-tick messages (macro/mod
 * spam) both observe the same context and run the handler twice — a double price set,
 * a double delete attempt. The claim is now an atomic remove at event time; the loser
 * sees null and falls through. A handler returning false re-arms its context, but a
 * newer request registered in between must win.
 */
class GuiChatInputClaimTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private GuiChatInputManager manager;
  private Player player;
  private UUID playerId;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    quickShopStatic.when(QuickShop::folia).thenReturn(mock(com.tcoded.folialib.FoliaLib.class));

    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(Bukkit.getPluginManager()).thenReturn(mock(PluginManager.class));
    lenient().when(plugin.getJavaPlugin()).thenReturn(mock(com.ghostchu.quickshop.QuickShopBukkit.class));

    // run the "1 tick later" region-thread task synchronously
    final var foliaLib = mock(com.tcoded.folialib.FoliaLib.class);
    final var scheduler = mock(com.tcoded.folialib.impl.PlatformScheduler.class);
    lenient().when(foliaLib.getScheduler()).thenReturn(scheduler);
    quickShopStatic.when(QuickShop::folia).thenReturn(foliaLib);
    lenient().doAnswer(inv->{
      final Runnable task = inv.getArgument(1);
      task.run();
      return null;
    }).when(scheduler).runAtEntityLater(any(org.bukkit.entity.Entity.class), any(Runnable.class), anyLong());

    manager = GuiChatInputManager.getInstance();
    player = mock(Player.class);
    playerId = UUID.nameUUIDFromBytes(new byte[]{5});
    lenient().when(player.getUniqueId()).thenReturn(playerId);
  }

  @AfterEach
  void tearDown() {

    manager.shutdown();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  private AsyncChatEvent chat(final String message) {

    final AsyncChatEvent event = mock(AsyncChatEvent.class);
    lenient().when(event.getPlayer()).thenReturn(player);
    when(event.message()).thenReturn(Component.text(message));
    return event;
  }

  @Test
  void sameTickMessagesApplyHandlerExactlyOnce() {

    final AtomicInteger applied = new AtomicInteger();
    manager.requestInput(player, msg->{
      applied.incrementAndGet();
      return true;
    }, null);

    manager.onChat(chat("100"));
    manager.onChat(chat("oops"));

    assertEquals(1, applied.get(),
                 "the second same-tick message must not run the handler a second time");
    assertFalse(manager.hasPendingInput(playerId), "accepted input must consume the pending entry");
  }

  @Test
  void declinedHandlerRearmsButNewerRequestWins() {

    final AtomicInteger first = new AtomicInteger();
    final AtomicInteger second = new AtomicInteger();

    manager.requestInput(player, msg->{
      first.incrementAndGet();
      return false; // wants more input
    }, null);
    manager.onChat(chat("keep-listening"));
    assertEquals(1, first.get());
    assertTrue(manager.hasPendingInput(playerId), "a declining handler must be re-armed");

    // while the declined task was in flight, a brand-new prompt took the slot
    manager.requestInput(player, msg->{
      second.incrementAndGet();
      return true;
    }, null);
    manager.onChat(chat("new-prompt"));

    assertEquals(1, first.get(), "the stale declined handler must not be re-armed over a newer prompt");
    assertEquals(1, second.get());
    assertFalse(manager.hasPendingInput(playerId));
  }

  @Test
  void cancelledEventRespectedWhenGateEnabled() {

    final var gatedPlugin = mock(QuickShop.class);
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean("shop.ignore-cancel-chat-event")).thenReturn(true);
    lenient().when(gatedPlugin.getConfig()).thenReturn(config);
    lenient().when(gatedPlugin.getJavaPlugin()).thenReturn(mock(com.ghostchu.quickshop.QuickShopBukkit.class));
    quickShopStatic.when(QuickShop::getInstance).thenReturn(gatedPlugin);

    final AtomicInteger applied = new AtomicInteger();
    final GuiChatInputManager gated = GuiChatInputManager.getInstance();
    try {
      gated.requestInput(player, msg->{
        applied.incrementAndGet();
        return true;
      }, null);

      final AsyncChatEvent cancelled = chat("100");
      when(cancelled.isCancelled()).thenReturn(true);
      gated.onChat(cancelled);

      assertEquals(0, applied.get(), "a cancelled event must be skipped when the gate is on");
      assertTrue(gated.hasPendingInput(playerId));
    } finally {
      gated.shutdown();
      quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    }
  }
}
