package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopAction;
import com.ghostchu.quickshop.api.shop.ShopInfoStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The 5-arg SimpleInfo constructor leaves shopData null; setAction can later flip an
 * info object into a trading action, and actionTrade calls hasChanged on it. The null
 * shopData must read as "changed" (fail safe) instead of throwing.
 */
class SimpleInfoNullShopDataTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;

  @BeforeEach
  void setUp() {

    // Shop's interface initializer resolves the plugin instance through Bukkit's
    // services manager; without the static mocks the mock(Shop.class) below fails
    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private ShopInfoStorage storage(final double price) {

    final QUser owner = mock(QUser.class);
    when(owner.serialize()).thenReturn("00000000-0000-0000-0000-000000000000;owner;false");
    return new ShopInfoStorage("world", null, owner, price, "item", 0, 0, "{}", false,
                               null, "Bukkit", "0;0;0;world", java.util.Collections.emptyMap());
  }

  @Test
  void nullShopDataFailsSafeAsChanged() {

    final SimpleInfo info = new SimpleInfo(mock(Location.class), ShopAction.CREATE_SELL,
                                           null, null, false);
    assertTrue(info.hasChanged(mock(Shop.class)), "null shopData must report changed, not NPE");
  }

  @Test
  void populatedShopDataComparesBySerialization() {

    final ShopInfoStorage storage = storage(10.0d);
    final Shop shop = mock(Shop.class);
    when(shop.saveToInfoStorage()).thenReturn(storage);

    final Shop sameShop = mock(Shop.class);
    when(sameShop.saveToInfoStorage()).thenReturn(storage);

    final SimpleInfo info = new SimpleInfo(mock(Location.class), ShopAction.PURCHASE_SELL,
                                           null, null, shop, false);
    assertFalse(info.hasChanged(sameShop));

    final Shop changedShop = mock(Shop.class);
    final ShopInfoStorage changedStorage = storage(12.5d);
    when(changedShop.saveToInfoStorage()).thenReturn(changedStorage);
    assertTrue(info.hasChanged(changedShop));
  }
}
