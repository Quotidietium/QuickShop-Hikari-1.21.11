package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.economy.EconomyManager;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.localization.text.Text;
import com.ghostchu.quickshop.api.localization.text.TextManager;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import dev.dejvokep.boostedyaml.YamlDocument;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * "all" in a free shop (price 0): balance/price used to evaluate 0/0 = NaN which cast
 * to 0 and made the calc report the player unable to afford a free trade. Every sibling
 * calculation (buyingShopAllCalc x2, getPlayerCanSell, getMaxAffordable) already guards
 * the zero price; the two sellingShopAllCalc copies must too.
 */
class FreeShopAllCalcTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private EconomyProvider eco;
  private Shop shop;
  private Player player;

  @BeforeEach
  void setUp() throws Exception {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    HandlerList.unregisterAll();

    final YamlDocument config = mock(YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getBoolean(anyString(), anyBoolean())).thenReturn(false);
    lenient().when(plugin.getConfig()).thenReturn(config);

    final java.lang.reflect.Field utilPlugin = Util.class.getDeclaredField("plugin");
    utilPlugin.setAccessible(true);
    utilPlugin.set(null, plugin);

    final TextManager textManager = mock(TextManager.class);
    final Text text = mock(Text.class);
    lenient().when(text.forLocale()).thenReturn(Component.empty());
    lenient().when(textManager.of(anyString(), any(Object[].class))).thenReturn(text);
    lenient().when(textManager.of(any(UUID.class), anyString(), any(Object[].class))).thenReturn(text);
    lenient().when(textManager.of(any(org.bukkit.command.CommandSender.class), anyString(), any(Object[].class))).thenReturn(text);
    lenient().when(plugin.text()).thenReturn(textManager);

    final var economyManager = mock(EconomyManager.class);
    eco = mock(EconomyProvider.class);
    lenient().when(economyManager.provider()).thenReturn(eco);
    lenient().when(plugin.getEconomyManager()).thenReturn(economyManager);
    lenient().when(eco.balance(any(QUser.class), anyString())).thenReturn(BigDecimal.ZERO);

    final SimpleShopManager manager = mock(SimpleShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(manager);
    doReturn("$1").when(manager).format(anyDouble(), any(Shop.class));

    final World world = mock(World.class);
    lenient().when(world.getName()).thenReturn("world");

    player = mock(Player.class);
    lenient().when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("free-shop-trader".getBytes()));
    lenient().when(player.getName()).thenReturn("free-shop-trader");
    final PlayerInventory playerInventory = mock(PlayerInventory.class);
    lenient().when(playerInventory.getStorageContents()).thenAnswer(inv->new ItemStack[36]);
    lenient().when(player.getInventory()).thenReturn(playerInventory);

    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.getType()).thenReturn(Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(1);
    lenient().when(item.getMaxStackSize()).thenReturn(64);

    shop = mock(Shop.class);
    lenient().when(shop.isUnlimited()).thenReturn(false);
    lenient().when(shop.getRemainingStock()).thenReturn(10);
    lenient().when(shop.bukkitLocation()).thenReturn(new Location(world, 0, 64, 0));
    lenient().when(shop.getItem()).thenReturn(item);
    lenient().when(shop.matches(any(ItemStack.class))).thenReturn(true);
    lenient().when(shop.getItemUnitSize()).thenReturn(1);
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  private int sellingShopAllCalc() throws Exception {

    final Method method = ShopUtil.class.getDeclaredMethod("sellingShopAllCalc", EconomyProvider.class, Shop.class, Player.class);
    method.setAccessible(true);
    try {
      return (Integer)method.invoke(null, eco, shop, player);
    } catch(final InvocationTargetException e) {
      throw (Exception)e.getCause();
    }
  }

  @Test
  void freeShopWithZeroBalanceStillTradesAll() throws Exception {

    // price 0 + balance 0 was exactly the 0/0 = NaN case that cast to 0
    lenient().when(shop.getPrice()).thenReturn(0.0d);
    lenient().when(shop.isFreeShop()).thenReturn(true);

    assertEquals(10, sellingShopAllCalc(), "free shop must cap by stock/space, not by a NaN balance division");
  }

  @Test
  void paidShopWithZeroBalanceStillRefuses() throws Exception {

    lenient().when(shop.getPrice()).thenReturn(5.0d);
    lenient().when(shop.isFreeShop()).thenReturn(false);

    assertEquals(0, sellingShopAllCalc(), "paid shop with empty balance must still refuse the trade");
  }

  @Test
  void freeShopWithPositiveBalanceStillTradesAll() throws Exception {

    lenient().when(shop.getPrice()).thenReturn(0.0d);
    lenient().when(shop.isFreeShop()).thenReturn(true);
    lenient().when(eco.balance(any(QUser.class), anyString())).thenReturn(BigDecimal.valueOf(100));

    assertEquals(10, sellingShopAllCalc());
  }
}
