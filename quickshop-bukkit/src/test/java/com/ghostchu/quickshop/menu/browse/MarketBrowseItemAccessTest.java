package com.ghostchu.quickshop.menu.browse;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.ItemMatcher;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.common.util.CommonUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the browse-menu item access costs: every shop.getItem() call is a
 * full defensive stack clone in production, and the NAME sort key re-derived a clone plus
 * a prettify string on every comparison. Grouping now reads one clone per shop (type gate
 * through the clone-free getMaterial, group probes through the package-private
 * uncloned representative), NAME sort decorates with memoized keys, and page building
 * reads materials without cloning. These tests pin the call-count contracts and the
 * ordering/grouping equivalence.
 */
class MarketBrowseItemAccessTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private ItemMatcher matcher;

  @BeforeEach
  void setUp() {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    final var plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    // Shop's interface initializer resolves QuickShopAPI.getPluginInstance(); install
    // the provider chain before the first Shop mock instruments the interface
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    // same-material matcher stand-in: grouping semantics only need "same family"
    matcher = mock(ItemMatcher.class);
    when(matcher.matches(any(ItemStack.class), any(ItemStack.class))).thenAnswer(inv -> {
      final ItemStack a = inv.getArgument(0, ItemStack.class);
      final ItemStack b = inv.getArgument(1, ItemStack.class);
      return a != null && b != null && a.getType() == b.getType();
    });
    when(plugin.getItemMatcher()).thenReturn(matcher);
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  private Shop shop(final Material material) {

    final Shop shop = mock(Shop.class);
    final ItemStack stack = mock(ItemStack.class);
    when(stack.getType()).thenReturn(material);
    when(stack.clone()).thenReturn(stack);
    when(shop.getItem()).thenReturn(stack);
    when(shop.getMaterial()).thenReturn(material);
    lenient().when(shop.isUnlimited()).thenReturn(false);
    lenient().when(shop.isSelling()).thenReturn(true);
    return shop;
  }

  @Test
  void testGroupingReadsOneItemClonePerShop() {

    // three materials over six shops: shop 4 reuses the DIAMOND group via two probes
    final List<Shop> shops = new ArrayList<>();
    shops.add(shop(Material.DIAMOND));
    shops.add(shop(Material.DIAMOND));
    shops.add(shop(Material.IRON_INGOT));
    shops.add(shop(Material.DIAMOND));
    shops.add(shop(Material.GOLD_INGOT));
    shops.add(shop(Material.IRON_INGOT));

    final List<MarketItemGroup> groups = MarketUtils.groupShopsByItem(shops);

    assertEquals(3, groups.size(), "one group per material family");
    assertEquals(List.of(shops.get(0), shops.get(1), shops.get(3)), groups.get(0).getShops(),
            "grouping contents and first-encounter order preserved");
    assertEquals(List.of(shops.get(2), shops.get(5)), groups.get(1).getShops());
    assertEquals(List.of(shops.get(4)), groups.get(2).getShops());
    // the core contract: exactly one defensive clone per shop (the baseline paid one for
    // the type gate, one per same-material group probe, and one for new-group builds)
    for(final Shop shop : shops) {
      verify(shop, times(1)).getItem();
    }
  }

  @Test
  void testNameSortOrdersByPrettifiedKeyAndStaysStable() {

    final List<Shop> shops = new ArrayList<>();
    shops.add(shop(Material.EMERALD));
    shops.add(shop(Material.DIAMOND));
    shops.add(shop(Material.COAL));
    shops.add(shop(Material.DIAMOND));   // equal key must keep encounter order
    shops.add(shop(Material.IRON_INGOT));

    final List<Shop> sorted = MarketUtils.sortShops(new ArrayList<>(shops), BrowseSortMode.NAME);

    // reference: the historical comparator semantics (stable sort by prettified name)
    final List<Shop> expected = new ArrayList<>(shops);
    expected.sort(Comparator.comparing(shop->CommonUtil.prettifyText(shop.getMaterial().name())));
    assertEquals(expected.stream().map(shop->shop.getMaterial()).toList(),
                 sorted.stream().map(shop->shop.getMaterial()).toList(),
                 "NAME ordering must match the historical comparator exactly");
    assertEquals(shops.get(1), sorted.get(1), "equal keys keep encounter order (stability)");
  }
}
