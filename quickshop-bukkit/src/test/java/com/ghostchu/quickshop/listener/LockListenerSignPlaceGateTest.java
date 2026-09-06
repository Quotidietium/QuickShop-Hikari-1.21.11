package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The R43 sign-placement gate: onSignPlace fires for every block placement server-wide
 * and used to pay {@code getState()} (a block-entity snapshot for placed chests etc.)
 * just to answer {@code instanceof Sign}. The material gate reorders that check — only
 * sign materials can produce a Sign state — so unrelated placements skip the state
 * fetch entirely and sign placements keep the original flow.
 */
class LockListenerSignPlaceGateTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Util> utilStatic;
  private QuickShop plugin;
  private LockListener listener;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    utilStatic = mockStatic(Util.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(plugin.getReloadManager())
            .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    listener = new LockListener(plugin);
  }

  @AfterEach
  void tearDown() {

    utilStatic.close();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  private BlockPlaceEvent placeEvent(final Block placed) {

    final BlockPlaceEvent event = mock(BlockPlaceEvent.class);
    // onSignPlace resolves the block through BlockEvent#getBlock()
    when(event.getBlock()).thenReturn(placed);
    return event;
  }

  @Test
  void nonSignPlacementSkipsTheStateFetch() {

    // a chest placement (block-entity snapshot in production) never reaches getState()
    final Block placed = mock(Block.class);
    when(placed.getType()).thenReturn(Material.CHEST);
    final var event = placeEvent(placed);

    listener.onSignPlace(event);

    verify(placed, never()).getState();
  }

  @Test
  void signPlacementKeepsTheOriginalFlow() {

    // a wall sign passes the gate, fetches the state and proceeds into the attached
    // block resolution exactly as before
    final Block placed = mock(Block.class);
    when(placed.getType()).thenReturn(Material.OAK_WALL_SIGN);
    when(placed.getState()).thenReturn(mock(Sign.class));
    utilStatic.when(() -> Util.getAttached(any(Block.class))).thenReturn(null);
    final var event = placeEvent(placed);

    listener.onSignPlace(event);

    verify(placed).getState();
  }
}
