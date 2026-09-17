package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.MockBukkit;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.localization.text.Text;
import com.ghostchu.quickshop.api.localization.text.TextManager;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.permission.PermissionManager;
import com.ghostchu.quickshop.api.shop.PriceLimiter;
import com.ghostchu.quickshop.api.shop.PriceLimiterCheckResult;
import com.ghostchu.quickshop.api.shop.PriceLimiterStatus;
import com.ghostchu.quickshop.shop.SimplePriceLimiterCheckResult;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the NOT_VALID arm of ShopUtil.setPrice's limiter switch. The keeper GUI's price
 * chat input parses arbitrary BigDecimal text (e.g. {@code 1e999}) and converts to an
 * infinite double; the limiter answers NOT_VALID for Infinity/NaN, but the switch used
 * to have no case for it and fell through to {@code shop.setPrice(event.updated())} —
 * storing the infinite price. /qs price and the creation path both pre-check; this was
 * the only open route.
 */
class ShopUtilSetPriceNotValidTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private Shop shop;
  private Player player;
  private UUID playerId;
  private TextManager textManager;
  private Text text;
  private PriceLimiterCheckResult notValid;

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    MockBukkit.install(bukkitStatic, plugin);
    HandlerList.unregisterAll();

    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getBoolean(anyString(), anyBoolean())).thenReturn(false);
    lenient().when(config.getInt(anyString(), any(Integer.class))).thenReturn(-1);
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.isPriceChangeRequiresFee()).thenReturn(false);

    textManager = mock(TextManager.class);
    text = mock(Text.class);
    lenient().when(text.forLocale()).thenReturn(Component.empty());
    lenient().when(textManager.of(anyString(), any(Object[].class))).thenReturn(text);
    lenient().when(textManager.of(anyString())).thenReturn(text);
    lenient().when(textManager.of(any(UUID.class), anyString(), any(Object[].class))).thenReturn(text);
    lenient().when(textManager.of(any(QUser.class), anyString(), any(Object[].class))).thenReturn(text);
    final ProxiedLocale locale = mock(ProxiedLocale.class);
    lenient().when(locale.getLocale()).thenReturn("en_us");
    lenient().when(textManager.findRelativeLanguages(any(org.bukkit.command.CommandSender.class))).thenReturn(locale);
    lenient().when(plugin.text()).thenReturn(textManager);
    lenient().when(plugin.getTextManager()).thenReturn(textManager);

    final PermissionManager perm = mock(PermissionManager.class);
    lenient().when(perm.hasPermission(any(org.bukkit.command.CommandSender.class), anyString())).thenReturn(true);
    lenient().when(plugin.perm()).thenReturn(perm);

    playerId = UUID.randomUUID();
    player = mock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(playerId);

    shop = mock(Shop.class);
    lenient().when(shop.getPrice()).thenReturn(10.0d);
    lenient().when(shop.playerAuthorize(any(UUID.class), any())).thenReturn(true);

    final PriceLimiter limiter = mock(PriceLimiter.class);
    notValid = new SimplePriceLimiterCheckResult(PriceLimiterStatus.NOT_VALID, 0d, 0d);
    lenient().when(limiter.check(any(QUser.class), any(), anyDouble())).thenReturn(notValid);

    final ShopManager manager = mock(ShopManager.class);
    lenient().when(manager.getPriceLimiter()).thenReturn(limiter);
    lenient().when(plugin.getShopManager()).thenReturn(manager);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private QUser user() {

    final QUser user = mock(QUser.class);
    lenient().when(user.getUniqueId()).thenReturn(playerId);
    lenient().when(user.getUsername()).thenReturn("tester");
    lenient().when(user.getBukkitPlayer()).thenReturn(Optional.of(player));
    return user;
  }

  @Test
  void testNotValidRejectsInfinityWithoutSettingPrice() {

    final QUser user = user();
    ShopUtil.setPrice(plugin, user, Double.POSITIVE_INFINITY, shop);

    // the infinite value must NOT reach the shop
    verify(shop, never()).setPrice(anyDouble());
    // the player gets the same not-a-number feedback the creation path uses
    final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(textManager).of(any(QUser.class), key.capture(), any(Object[].class));
    assertEquals("not-a-number", key.getValue());
  }

  @Test
  void testNotValidRejectsNaNSilentlyWithoutSettingPrice() {

    ShopUtil.setPrice(plugin, user(), Double.NaN, shop);
    verify(shop, never()).setPrice(anyDouble());
  }

  @Test
  void voidTestSanity() {

    // sanity: the NOT_VALID result we stub really carries the status under test
    assertSame(PriceLimiterStatus.NOT_VALID, notValid.getStatus());
  }
}
