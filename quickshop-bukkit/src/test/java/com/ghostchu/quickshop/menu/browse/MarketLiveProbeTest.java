package com.ghostchu.quickshop.menu.browse;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.obj.QUserImpl;
import com.ghostchu.quickshop.shop.ContainerShop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import com.ghostchu.quickshop.util.matcher.item.QuickShopItemMatcherImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the browse pipeline's live-probe reads: grouping, search and the
 * grouped page used to pay one defensive {@code getItem()} stack copy per shop per pass
 * (an NBT deep copy in production) plus re-clones when building each group's
 * representative. Under the builtin read-only matcher those passes now read the live
 * prototype through {@link ContainerShop#getItemDirect()}, cloning only once per NEW
 * group for its private representative; third-party matchers and third-party shop
 * implementations keep the historical defensive copies. These tests pin the clone-count
 * contracts and the grouping/search equivalence.
 */
class MarketLiveProbeTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private World world;

  @BeforeEach
  void setUp() throws java.io.IOException {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    final PluginManager pluginManager = mock(PluginManager.class);
    bukkitStatic.when(Bukkit::isPrimaryThread).thenReturn(true);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    org.bukkit.event.HandlerList.unregisterAll();

    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getInt(anyString())).thenReturn(0);
    lenient().when(plugin.getConfig()).thenReturn(config);
    final var platform = mock(com.ghostchu.quickshop.platform.Platform.class);
    lenient().when(platform.getItemShopId(any(ItemStack.class))).thenReturn(null);
    lenient().when(plugin.platform()).thenReturn(platform);
    // empty matcher config -> work type 0 / default meta matcher (MenuBench shape)
    lenient().when(config.getSection("matcher.item"))
            .thenReturn(mock(dev.dejvokep.boostedyaml.block.implementation.Section.class));

    // group statistics read the cached counts, never the live inventory
    final var shopManager = mock(SimpleShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
    lenient().when(shopManager.queryShopInventoryCacheInDatabase(any(com.ghostchu.quickshop.api.shop.Shop.class)))
            .thenAnswer(inv->CompletableFuture.completedFuture(
                    new com.ghostchu.quickshop.shop.cache.SimpleShopInventoryCountCache(64, 64, true)));

    world = mock(World.class);
    when(world.getName()).thenReturn("world");
  }

  @AfterEach
  void tearDown() {

    org.bukkit.event.HandlerList.unregisterAll();
    bukkitStatic.close();
    quickShopStatic.close();
  }

  /**
   * Counting stack: the constructor's defensive snapshot becomes the live field copy;
   * every later clone() on either mock is observable. The family answer mirrors the
   * benchmark fixtures' same-material semantics.
   */
  private ItemStack countingStack(final Material material) {

    final ItemStack stack = mock(ItemStack.class);
    final ItemStack fieldCopy = mock(ItemStack.class);
    when(stack.getType()).thenReturn(material);
    when(fieldCopy.getType()).thenReturn(material);
    when(stack.hasItemMeta()).thenReturn(false);
    when(fieldCopy.hasItemMeta()).thenReturn(false);
    when(stack.getAmount()).thenReturn(1);
    when(fieldCopy.getAmount()).thenReturn(1);
    when(stack.clone()).thenReturn(fieldCopy);
    when(fieldCopy.clone()).thenReturn(fieldCopy);
    when(fieldCopy.isSimilar(any(ItemStack.class))).thenAnswer(inv->{
      final ItemStack other = inv.getArgument(0, ItemStack.class);
      return other != null && other.getType() == material && !other.hasItemMeta();
    });
    return stack;
  }

  private ContainerShop shop(final ItemStack item) {

    final var owner = QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("probe-owner".getBytes()), "probe-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    final ContainerShop created = new ContainerShop(
            plugin, -1L, new Location(world, 1, 64, 1), 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), false, null,
            "Bukkit", "sym-probe", null,
            new HashMap<>(), benefit);
    // the construction snapshot happened during the constructor; what happens after this
    // point is what the pipeline under test pays
    clearInvocations(item, created.getItemDirect());
    return created;
  }

  private void installBuiltinMatcher() {

    // construct outside when(...): the matcher's constructor re-reads the plugin config
    final var builtin = new QuickShopItemMatcherImpl(plugin);
    when(plugin.getItemMatcher()).thenReturn(builtin);
  }

  @Test
  void getItemDirectIsTheStableLivePrototype() {

    final ItemStack stack = countingStack(Material.DIAMOND);
    final ContainerShop shop = shop(stack);

    // same instance across calls, and distinct from the defensive getItem() copies
    assertSame(shop.getItemDirect(), shop.getItemDirect());
    assertEquals(Material.DIAMOND, shop.getItemDirect().getType());
    // probing never cloned the construction stack
    verify(stack, times(0)).clone();
    verify(shop.getItemDirect(), times(0)).clone();
  }

  @Test
  void groupingClonesOncePerNewGroupAndNeverPerShop() {

    installBuiltinMatcher();

    final ContainerShop diamondA = shop(countingStack(Material.DIAMOND));
    final ContainerShop diamondB = shop(countingStack(Material.DIAMOND));
    final ContainerShop ironA = shop(countingStack(Material.IRON_INGOT));
    final ContainerShop ironB = shop(countingStack(Material.IRON_INGOT));
    final ContainerShop gold = shop(countingStack(Material.GOLD_INGOT));
    final ContainerShop diamondC = shop(countingStack(Material.DIAMOND));

    final List<MarketItemGroup> groups = MarketUtils.groupShopsByItem(
            List.of(diamondA, diamondB, ironA, ironB, gold, diamondC));

    assertEquals(3, groups.size(), "one group per material family");
    assertEquals(List.of(diamondA, diamondB, diamondC), groups.get(0).getShops(),
            "grouping contents and first-encounter order preserved");
    assertEquals(List.of(ironA, ironB), groups.get(1).getShops());
    assertEquals(List.of(gold), groups.get(2).getShops());

    // the clone-count contract: the FIRST shop of each family pays the group's single
    // ownership copy (on its live prototype); every joining shop clones nothing — the
    // baseline paid one defensive copy per shop plus one per new group
    verify(diamondA.getItemDirect(), times(1)).clone();
    verify(diamondB.getItemDirect(), times(0)).clone();
    verify(diamondC.getItemDirect(), times(0)).clone();
    verify(ironA.getItemDirect(), times(1)).clone();
    verify(ironB.getItemDirect(), times(0)).clone();
    verify(gold.getItemDirect(), times(1)).clone();
  }

  @Test
  void searchProbesTheLivePrototypeAndKeepsSemantics() {

    final ContainerShop namedShop = shop(countingStack(Material.DIAMOND));
    // meta lives on the live prototype the search probe reads
    final ItemStack namedField = namedShop.getItemDirect();
    final ItemMeta meta = mock(ItemMeta.class);
    when(namedField.hasItemMeta()).thenReturn(true);
    when(namedField.getItemMeta()).thenReturn(meta);
    when(meta.hasDisplayName()).thenReturn(true);
    when(meta.getDisplayName()).thenReturn("Custom Sword");
    final ContainerShop plainShop = shop(countingStack(Material.IRON_INGOT));

    assertEquals(List.of(namedShop), MarketUtils.searchShops(List.of(namedShop, plainShop), "sword"),
            "custom display name still matches after the single-meta rewrite");
    assertEquals(List.of(plainShop), MarketUtils.searchShops(List.of(namedShop, plainShop), "iron"),
            "material-name matching unchanged");

    // the live probe clones nothing on either prototype (baseline cloned once per shop
    // per search); the meta path reads getItemMeta()'s own copy
    verify(namedShop.getItemDirect(), times(0)).clone();
    verify(plainShop.getItemDirect(), times(0)).clone();
  }

  @Test
  void thirdPartyMatcherKeepsThePerShopDefensiveCopy() {

    // a stand-in matcher that is NOT the builtin implementation must keep receiving
    // defensive copies (it carries no read-only contract)
    final var standIn = mock(com.ghostchu.quickshop.api.shop.ItemMatcher.class);
    when(standIn.matches(any(ItemStack.class), any(ItemStack.class))).thenAnswer(inv->{
      final ItemStack a = inv.getArgument(0, ItemStack.class);
      final ItemStack b = inv.getArgument(1, ItemStack.class);
      return a != null && b != null && a.getType() == b.getType();
    });
    when(plugin.getItemMatcher()).thenReturn(standIn);

    final ContainerShop a = shop(countingStack(Material.DIAMOND));
    final ContainerShop b = shop(countingStack(Material.DIAMOND));

    final List<MarketItemGroup> groups = MarketUtils.groupShopsByItem(List.of(a, b));

    assertEquals(1, groups.size());
    // the historical defensive contract: one getItem() copy per shop, plus the new
    // group's ownership copy — foreign matchers carry no read-only guarantee
    verify(a.getItemDirect(), times(2)).clone();
    verify(b.getItemDirect(), times(1)).clone();
  }
}
