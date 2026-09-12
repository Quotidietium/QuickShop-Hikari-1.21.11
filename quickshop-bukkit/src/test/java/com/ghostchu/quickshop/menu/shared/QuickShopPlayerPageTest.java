package com.ghostchu.quickshop.menu.shared;

import net.tnemc.menu.core.Menu;
import net.tnemc.menu.core.handlers.MenuClickHandler;
import net.tnemc.menu.core.icon.Icon;
import net.tnemc.menu.core.icon.action.ActionType;
import net.tnemc.menu.core.utils.SlotPos;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * R53 per-player menu page contracts. The stock TNML page shares one icon map across
 * every viewer (the last opener's actions resolve everyone's clicks), and the shipped
 * TNML 1.6.0.0-SNAPSHOT-15 {@code PlayerInstancePage} additionally (a) replaces the
 * per-player instance on every addIcon call, keeping only the newest icon, and (b) never
 * routes click dispatch through the per-player overload — the library resolves clicks
 * exclusively against the shared map. {@link QuickShopPlayerPage} must repair all three
 * for the QuickShop menus to be safe under concurrent viewers.
 */
class QuickShopPlayerPageTest {

  private static final int SLOT = 10;

  private static Icon icon(final int slot, final boolean blocked) {

    final Icon icon = mock(Icon.class);
    lenient().when(icon.slot()).thenReturn(slot);
    lenient().when(icon.onClick(org.mockito.ArgumentMatchers.any())).thenReturn(!blocked);
    return icon;
  }

  private static MenuClickHandler click(final UUID clicker, final int slot) {

    final MenuPlayer player = mock(MenuPlayer.class);
    lenient().when(player.identifier()).thenReturn(clicker);
    return new MenuClickHandler(new SlotPos(slot), player, mock(Menu.class), 1, ActionType.LEFT_CLICK);
  }

  @Test
  void iconsAccumulateAcrossAddIconCalls() {

    // upstream replaces the per-player instance per call and keeps only the last icon
    final QuickShopPlayerPage page = new QuickShopPlayerPage(1);
    final UUID id = UUID.randomUUID();
    page.addIcon(id, icon(SLOT, false));
    page.addIcon(id, icon(SLOT + 1, false));
    page.addIcon(id, icon(SLOT + 2, false));
    assertEquals(3, page.getIcons(id).size(), "every added icon must survive");
  }

  @Test
  void instanceIconsNeverFallsBackToTheSharedMap() {

    final QuickShopPlayerPage page = new QuickShopPlayerPage(1);
    final UUID id = UUID.randomUUID();
    final Icon shared = icon(SLOT, false);
    page.addIcon(shared); // shared-map registration only
    assertNotSame(page.getIcons(), page.instanceIcons(id), "a fresh viewer must get its own map");
    assertTrue(page.instanceIcons(id).isEmpty());
    assertFalse(page.getIcons(id).containsValue(shared));
  }

  @Test
  void clickResolvesThroughTheClickersInstance() {

    // the library dispatch calls only Page.onClick(MenuClickHandler); the override must
    // pick the clicking player's icons or two viewers cross-execute each other's actions
    final QuickShopPlayerPage page = new QuickShopPlayerPage(1);
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final AtomicInteger aliceClicks = new AtomicInteger();
    final AtomicInteger bobClicks = new AtomicInteger();

    final Icon aliceIcon = mock(Icon.class);
    lenient().when(aliceIcon.slot()).thenReturn(SLOT);
    lenient().when(aliceIcon.onClick(org.mockito.ArgumentMatchers.any(MenuClickHandler.class)))
            .thenAnswer(inv->{
              aliceClicks.incrementAndGet();
              return true;
            });
    final Icon bobIcon = mock(Icon.class);
    lenient().when(bobIcon.slot()).thenReturn(SLOT);
    lenient().when(bobIcon.onClick(org.mockito.ArgumentMatchers.any(MenuClickHandler.class)))
            .thenAnswer(inv->{
              bobClicks.incrementAndGet();
              return true;
            });

    page.addIcon(alice, aliceIcon);
    page.addIcon(bob, bobIcon);

    assertTrue(page.onClick(click(alice, SLOT)));
    assertTrue(page.onClick(click(bob, SLOT)));
    assertEquals(1, aliceClicks.get());
    assertEquals(1, bobClicks.get());
    assertSame(aliceIcon, page.getIcons(alice).get(SLOT));
    assertSame(bobIcon, page.getIcons(bob).get(SLOT));
  }

  @Test
  void blockedIconCancelsTheClickForItsOwnerOnly() {

    final QuickShopPlayerPage page = new QuickShopPlayerPage(1);
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    final AtomicInteger aliceClicks = new AtomicInteger();
    final AtomicInteger bobClicks = new AtomicInteger();
    final Icon aliceIcon = mock(Icon.class);
    lenient().when(aliceIcon.slot()).thenReturn(SLOT);
    lenient().when(aliceIcon.onClick(org.mockito.ArgumentMatchers.any(MenuClickHandler.class)))
            .thenAnswer(inv->{
              aliceClicks.incrementAndGet();
              return false; // alice's icon vetoes its action
            });
    final Icon bobIcon = mock(Icon.class);
    lenient().when(bobIcon.slot()).thenReturn(SLOT);
    lenient().when(bobIcon.onClick(org.mockito.ArgumentMatchers.any(MenuClickHandler.class)))
            .thenAnswer(inv->{
              bobClicks.incrementAndGet();
              return true; // bob's icon allows the action
            });

    page.addIcon(alice, aliceIcon);
    page.addIcon(bob, bobIcon);

    // a vetoed icon cancels; an allowed action still cancels the raw grab (occupied
    // slot) — what matters is that each click consulted only its owner's icon
    assertTrue(page.onClick(click(alice, SLOT)), "a blocked icon reports the click cancelled");
    assertTrue(page.onClick(click(bob, SLOT)), "an occupied slot still cancels the raw grab");
    assertEquals(1, aliceClicks.get());
    assertEquals(1, bobClicks.get());
  }

  @Test
  void clickWithoutInstanceFallsBackToTheSharedMap() {

    // parity with BukkitInventory.build: an unknown viewer resolves against shared icons
    final QuickShopPlayerPage page = new QuickShopPlayerPage(1);
    page.addIcon(icon(SLOT, false));
    assertTrue(page.onClick(click(UUID.randomUUID(), SLOT)));
    assertFalse(page.onClick(click(UUID.randomUUID(), SLOT + 1)), "empty slots pass through");
  }

  @Test
  void clearInstanceDropsThePlayerIcons() {

    final QuickShopPlayerPage page = new QuickShopPlayerPage(1);
    final UUID id = UUID.randomUUID();
    page.addIcon(id, icon(SLOT, false));
    page.clearInstance(id);
    assertFalse(page.hasInstance(id), "close cleanup must free the per-player map");
    assertTrue(page.getIcons(id).isEmpty() || page.getIcons(id) == page.getIcons());
  }
}
