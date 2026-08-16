package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.localization.text.TextManager;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trade-path sign scheduling: entries keep the requesting player's locale, repeated
 * schedules for one shop coalesce into a single refresh, and entries without a locale
 * fall back to the owner-relative locale exactly like the hopper path.
 */
class TradeSignSchedulingTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private Shop shop;
  private SignUpdateWatcher watcher;

  @BeforeEach
  void setUp() {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    quickShopStatic.when(QuickShop::folia).thenReturn(mock(com.tcoded.folialib.FoliaLib.class));

    shop = mock(Shop.class);
    watcher = new SignUpdateWatcher();
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  @Test
  void coalescedEntriesRenderOnceWithRequesterLocale() {

    final ProxiedLocale buyerLocale = mock(ProxiedLocale.class);
    for(int i = 0; i < 10; i++) {
      watcher.scheduleSignUpdate(shop, buyerLocale);
    }
    watcher.run();

    verify(shop, times(1)).setSignText(buyerLocale);
  }

  @Test
  void nullLocaleFallsBackToOwnerRelativeLocale() {

    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("sign-owner".getBytes()), "sign-owner", true);
    when(shop.getOwner()).thenReturn(owner);
    final ProxiedLocale ownerLocale = mock(ProxiedLocale.class);
    final TextManager textManager = mock(TextManager.class);
    when(plugin.text()).thenReturn(textManager);
    when(plugin.getTextManager()).thenReturn(textManager);
    when(textManager.findRelativeLanguages(any(QUser.class), eq(false))).thenReturn(ownerLocale);

    watcher.scheduleSignUpdate(shop);
    watcher.run();

    verify(shop, times(1)).setSignText(ownerLocale);
  }
}
