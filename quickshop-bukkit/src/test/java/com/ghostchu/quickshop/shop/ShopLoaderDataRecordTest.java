package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.database.bean.DataRecord;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.platform.Platform;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression test for the shop tax-account load path: a tax account stored in the data
 * record must survive loading into DataRawDatabaseInfo (it previously always loaded as
 * null and the null was then persisted back, permanently erasing the setting).
 */
class ShopLoaderDataRecordTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;

  @BeforeEach
  void setUp() {

    final QuickShop plugin = mock(QuickShop.class);
    final ShopManager shopManager = mock(ShopManager.class);
    when(plugin.getShopManager()).thenReturn(shopManager);
    when(shopManager.shopTypeOrDefault(org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(SimpleShopManager.BUYING_TYPE);
    when(shopManager.shopTypeOrDefault(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(SimpleShopManager.BUYING_TYPE);
    when(shopManager.shopStateOrDefault(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(SimpleShopManager.ACTIVE_STATE);
    final Platform platform = mock(Platform.class);
    when(plugin.platform()).thenReturn(platform);
    final ItemStack decoded = mock(ItemStack.class);
    when(decoded.getAmount()).thenReturn(1);
    when(platform.decodeStack(org.mockito.ArgumentMatchers.anyString())).thenReturn(decoded);

    bukkitStatic = mockStatic(Bukkit.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private DataRecord recordWithTaxAccount(final QUser taxAccount) {

    final DataRecord record = mock(DataRecord.class);
    when(record.getTaxAccount()).thenReturn(taxAccount);
    when(record.getEncoded()).thenReturn("encoded-stack");
    when(record.getPermissions()).thenReturn(null);
    when(record.getBenefit()).thenReturn(null);
    when(record.getExtra()).thenReturn(null);
    return record;
  }

  @Test
  void taxAccountSurvivesLoading() {

    final QUser taxAccount = mock(QUser.class);
    final ShopLoader.DataRawDatabaseInfo info = new ShopLoader.DataRawDatabaseInfo(recordWithTaxAccount(taxAccount));
    assertEquals(taxAccount, info.getTaxAccount(), "shop tax account must be read from the data record, not the nulled local field");
  }

  @Test
  void missingTaxAccountStaysNull() {

    final ShopLoader.DataRawDatabaseInfo info = new ShopLoader.DataRawDatabaseInfo(recordWithTaxAccount(null));
    assertNull(info.getTaxAccount());
  }
}
