package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.database.DatabaseHelper;
import com.ghostchu.quickshop.api.event.inventory.ShopInventoryCalculateEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.simplereloadlib.ReloadManager;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the inventory-profile cache: unchanged space/stock values must not
 * re-issue the database update (the SpaceCache constructor args were previously swapped,
 * making the dedup comparison fail on every event).
 */
class InternalListenerTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private DatabaseHelper databaseHelper;
  private InternalListener listener;

  @BeforeEach
  void setUp() {

    final QuickShop plugin = mock(QuickShop.class);
    databaseHelper = mock(DatabaseHelper.class);
    when(plugin.getDatabaseHelper()).thenReturn(databaseHelper);
    when(plugin.getReloadManager()).thenReturn(mock(ReloadManager.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    when(plugin.getConfig()).thenReturn(config);
    when(config.getBoolean(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
    when(databaseHelper.updateExternalInventoryProfileCache(anyLong(), anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(1));

    bukkitStatic = mockStatic(Bukkit.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);

    listener = new InternalListener(plugin);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private Shop shopWithId(final long id) {

    final Shop shop = mock(Shop.class);
    when(shop.getShopId()).thenReturn(id);
    return shop;
  }

  @Test
  void unchangedInventoryNumbersDoNotRewriteTheDatabase() {

    final Shop shop = shopWithId(42);
    listener.shopInventoryCalc(new ShopInventoryCalculateEvent(shop, 7, 13));
    listener.shopInventoryCalc(new ShopInventoryCalculateEvent(shop, 7, 13));
    listener.shopInventoryCalc(new ShopInventoryCalculateEvent(shop, 7, 13));

    verify(databaseHelper, times(1)).updateExternalInventoryProfileCache(42, 7, 13);
  }

  @Test
  void changedInventoryNumbersRewriteTheDatabase() {

    final Shop shop = shopWithId(42);
    listener.shopInventoryCalc(new ShopInventoryCalculateEvent(shop, 7, 13));
    listener.shopInventoryCalc(new ShopInventoryCalculateEvent(shop, 8, 13));
    listener.shopInventoryCalc(new ShopInventoryCalculateEvent(shop, 8, 20));

    verify(databaseHelper, times(1)).updateExternalInventoryProfileCache(42, 7, 13);
    verify(databaseHelper, times(1)).updateExternalInventoryProfileCache(42, 8, 13);
    verify(databaseHelper, times(1)).updateExternalInventoryProfileCache(42, 8, 20);
  }

  @Test
  void uncreatedShopsAreSkipped() {

    listener.shopInventoryCalc(new ShopInventoryCalculateEvent(shopWithId(0), 7, 13));
    verify(databaseHelper, times(0)).updateExternalInventoryProfileCache(anyLong(), anyInt(), anyInt());
  }
}
