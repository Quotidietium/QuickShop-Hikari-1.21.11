package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Info;
import com.ghostchu.quickshop.api.shop.ShopAction;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Equivalence tests for the shared move/teleport session-cancel body: both handlers
 * measure the player's CURRENT position (during a teleport event that is still the
 * departure point — the historic behavior both paths shared), cancel the interactive
 * session with the trading message when out of range, and leave near players alone.
 * The teleport handler must behave exactly like the move handler without synthesizing
 * a PlayerMoveEvent.
 */
class PlayerListenerMoveTeleportTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private PlayerListener listener;
  private ShopManager.InteractiveManager interactive;
  private Player player;
  private World world;
  private Info info;
  private com.ghostchu.quickshop.api.localization.text.TextManager text;

  @BeforeEach
  void setUp() throws java.io.IOException {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-move-test").toFile());
    final YamlDocument config = mock(YamlDocument.class);
    lenient().when(config.getBoolean(any(String.class))).thenReturn(false);
    lenient().when(plugin.getConfig()).thenReturn(config);

    final SimpleShopManager shopManager = mock(SimpleShopManager.class);
    interactive = mock(ShopManager.InteractiveManager.class);
    lenient().when(shopManager.getInteractiveManager()).thenReturn(interactive);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);

    text = mock(com.ghostchu.quickshop.api.localization.text.TextManager.class);
    final var message = mock(com.ghostchu.quickshop.api.localization.text.Text.class);
    lenient().when(text.of(any(org.bukkit.command.CommandSender.class), anyString())).thenReturn(message);
    lenient().when(plugin.text()).thenReturn(text);

    bukkitStatic.when(() -> Bukkit.getTag(any(String.class), any(org.bukkit.NamespacedKey.class), any(Class.class)))
            .thenAnswer(inv -> mock(org.bukkit.Tag.class));

    listener = new PlayerListener(plugin);
    player = mock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());

    world = mock(World.class);
    lenient().when(world.getName()).thenReturn("world");

    info = mock(Info.class);
    lenient().when(info.getLocation()).thenReturn(new Location(world, 100, 64, 100));
    lenient().when(info.getAction()).thenReturn(ShopAction.PURCHASE_BUY);
    lenient().when(interactive.get(player.getUniqueId())).thenReturn(info);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private PlayerMoveEvent move(final Location from, final Location to) {

    return new PlayerMoveEvent(player, from, to);
  }

  /** The test classpath runs the inline mock maker, which intercepts the final
   *  PlayerEvent.getPlayer() — a plain stub beats the field-injection trick here. */
  private PlayerTeleportEvent teleport() {

    final PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
    lenient().when(event.getPlayer()).thenReturn(player);
    return event;
  }

  @Test
  void farMoveCancelsTheTradingSession() {

    when(player.getLocation()).thenReturn(new Location(world, 200, 64, 200));
    listener.onMove(move(new Location(world, 100, 64, 100), new Location(world, 200, 64, 200)));

    verify(text).of(player, "shop-purchase-cancelled");
    verify(interactive).remove(player.getUniqueId());
  }

  @Test
  void nearMoveKeepsTheSession() {

    when(player.getLocation()).thenReturn(new Location(world, 102, 64, 101));
    listener.onMove(move(new Location(world, 100, 64, 100), new Location(world, 102, 64, 101)));

    verify(text, never()).of(any(org.bukkit.command.CommandSender.class), anyString());
    verify(interactive, never()).remove(any(UUID.class));
  }

  @Test
  void farTeleportCancelsLikeAFarMove() {

    // during the teleport event the player is still AT the departure position; the
    // historic synthesized-move behavior measured exactly that position
    when(player.getLocation()).thenReturn(new Location(world, 140, 64, 140));
    final var event = teleport();
    listener.onTeleport(event);

    verify(text).of(player, "shop-purchase-cancelled");
    verify(interactive).remove(player.getUniqueId());
  }

  @Test
  void teleportToFarDestinationButStillStandingNearKeepsTheSession() {

    // the destination is far, but the departure position is inside the radius — the
    // historic behavior kept the session (the check ran against the current position)
    when(player.getLocation()).thenReturn(new Location(world, 101, 64, 101));
    final var event = teleport();
    lenient().when(event.getTo()).thenReturn(new Location(world, 900, 64, 900));
    listener.onTeleport(event);

    verify(text, never()).of(any(org.bukkit.command.CommandSender.class), anyString());
    verify(interactive, never()).remove(any(UUID.class));
  }

  @Test
  void playerWithoutSessionIsIgnored() {

    when(interactive.get(player.getUniqueId())).thenReturn(null);
    when(player.getLocation()).thenReturn(new Location(world, 900, 64, 900));
    listener.onMove(move(new Location(world, 900, 64, 900), new Location(world, 900, 64, 901)));
    listener.onTeleport(teleport());

    verify(interactive, never()).remove(any(UUID.class));
  }
}
