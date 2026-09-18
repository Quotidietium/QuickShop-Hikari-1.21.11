package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.MockBukkit;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.localization.text.Text;
import com.ghostchu.quickshop.api.localization.text.TextManager;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the accept-time ownership re-validation of pending transfer requests. The 60s
 * taskCache window used to commit {@code setOwner(to)} unconditionally: a requester
 * who reassigned the shop in between (or an admin /qs setowner) lost the shop to the
 * receiver, who never had any authority over it.
 */
class PendingTransferOwnershipGuardTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private QUser from;
  private QUser to;
  private QUser other;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    MockBukkit.install(bukkitStatic, plugin);
    HandlerList.unregisterAll();
    bukkitStatic.when(Bukkit::isPrimaryThread).thenReturn(true);

    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));

    final TextManager textManager = mock(TextManager.class);
    final Text text = mock(Text.class);
    lenient().when(text.forLocale()).thenReturn(Component.empty());
    lenient().when(textManager.of(any(QUser.class), anyString(), any(Object[].class))).thenReturn(text);
    final ProxiedLocale locale = mock(ProxiedLocale.class);
    lenient().when(textManager.findRelativeLanguages(any(org.bukkit.command.CommandSender.class))).thenReturn(locale);
    lenient().when(plugin.text()).thenReturn(textManager);

    // real QUserImpl instances: equals compares uniqueId, exactly the identity the
    // accept-time guard relies on (stubbing equals() on mocks is not supported)
    from = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(UUID.randomUUID(), "requester", true);
    to = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(UUID.randomUUID(), "receiver", true);
    other = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(UUID.randomUUID(), "newowner", true);
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  private Shop shopOwnedBy(final QUser owner, final AtomicInteger setOwnerCalls) {

    final Shop shop = mock(Shop.class);
    when(shop.getOwner()).thenReturn(owner);
    lenient().doAnswer(inv->{
      setOwnerCalls.incrementAndGet();
      return null;
    }).when(shop).setOwner(any(QUser.class));
    return shop;
  }

  @Test
  void stillOwnedShopTransfers() {

    final AtomicInteger transfers = new AtomicInteger();
    final Shop shop = shopOwnedBy(from, transfers);

    new ShopUtil.PendingTransferTask(from, to, List.of(shop)).commit(false);

    assertEquals(1, transfers.get());
    verify(shop).setOwner(to);
  }

  @Test
  void reassignedShopIsSkippedNotHijacked() {

    final AtomicInteger transfers = new AtomicInteger();
    final Shop shop = shopOwnedBy(other, transfers);

    new ShopUtil.PendingTransferTask(from, to, List.of(shop)).commit(false);

    assertEquals(0, transfers.get());
    verify(shop, never()).setOwner(any(QUser.class));
  }

  @Test
  void mixedListTransfersOnlyStillOwnedShops() {

    final AtomicInteger transfers = new AtomicInteger();
    final Shop kept = shopOwnedBy(from, transfers);
    final Shop lost = shopOwnedBy(other, transfers);

    new ShopUtil.PendingTransferTask(from, to, List.of(kept, lost)).commit(false);

    assertEquals(1, transfers.get());
    verify(kept).setOwner(to);
    verify(lost, never()).setOwner(any(QUser.class));
  }

  @Test
  void missingOwnerMappingIsSkippedToo() {

    // defensive: a shop whose owner cannot be resolved must not fall through to transfer
    final AtomicInteger transfers = new AtomicInteger();
    final Shop shop = mock(Shop.class);
    when(shop.getOwner()).thenReturn(null);
    lenient().doAnswer(inv->{
      transfers.incrementAndGet();
      return null;
    }).when(shop).setOwner(any(QUser.class));

    new ShopUtil.PendingTransferTask(from, to, List.of(shop)).commit(false);

    assertEquals(0, transfers.get());
    verify(shop, never()).setOwner(eq(to));
  }
}
