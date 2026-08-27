package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R28 chat-gate snapshot: every chat message on the server
 * passes the handler, and cancelled ones (mute/filter plugins) must not walk the config
 * tree for the gate flag — it is snapshotted at construction and refreshed on reload
 * only. Observable behavior flips through the interactive-manager branch, which a true
 * gate never reaches for cancelled events.
 */
class ChatListenerTest {

  private org.mockito.MockedStatic<Bukkit> bukkitStatic;
  private org.mockito.MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private YamlDocument config;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();
  private com.ghostchu.quickshop.api.shop.ShopManager.InteractiveManager interactiveManager;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(plugin.getReloadManager())
            .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getBoolean(anyString())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Boolean bool && bool;
    });
    lenient().when(config.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      if(value instanceof final Boolean bool) {
        return bool;
      }
      return inv.getArgument(1, Boolean.class);
    });
    lenient().when(plugin.getConfig()).thenReturn(config);

    interactiveManager = mock(com.ghostchu.quickshop.api.shop.ShopManager.InteractiveManager.class);
    lenient().when(interactiveManager.containsKey(any(UUID.class))).thenReturn(false);
    final var shopManager = mock(com.ghostchu.quickshop.shop.SimpleShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
    lenient().when(shopManager.getInteractiveManager()).thenReturn(interactiveManager);

    lenient().when(Bukkit.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private AsyncPlayerChatEvent cancelledChat() {

    final var player = mock(org.bukkit.entity.Player.class);
    lenient().when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(new byte[]{1}));
    final var event = mock(AsyncPlayerChatEvent.class);
    when(event.isCancelled()).thenReturn(true);
    when(event.getPlayer()).thenReturn(player);
    return event;
  }

  @Test
  void cancelledEventsConsumeTheSnapshotNotTheConfigTree() {

    configValues.put("shop.ignore-cancel-chat-event", true);
    final ChatListener listener = new ChatListener(plugin);

    listener.onChat(cancelledChat());
    listener.onChat(cancelledChat());

    // one read at construction only; neither event walked the config tree
    verify(config, times(1)).getBoolean("shop.ignore-cancel-chat-event");
    verify(interactiveManager, never()).containsKey(any(UUID.class));
  }

  @Test
  void gateFalseFallsThroughToInteractiveLookup() {

    configValues.put("shop.ignore-cancel-chat-event", false);
    final ChatListener listener = new ChatListener(plugin);

    listener.onChat(cancelledChat());

    verify(interactiveManager, times(1)).containsKey(any(UUID.class));
  }

  @Test
  void reloadRefreshesTheSnapshotWithoutReconstruction() {

    configValues.put("shop.ignore-cancel-chat-event", true);
    final ChatListener listener = new ChatListener(plugin);

    listener.onChat(cancelledChat());
    verify(interactiveManager, never()).containsKey(any(UUID.class));

    configValues.remove("shop.ignore-cancel-chat-event");
    listener.reloadModule();
    listener.onChat(cancelledChat());

    // absent key == false (original getBoolean(path) semantics): the same cancelled
    // event now falls through to the interactive lookup
    verify(interactiveManager, times(1)).containsKey(any(UUID.class));
    verify(config, times(2)).getBoolean("shop.ignore-cancel-chat-event");
  }
}
