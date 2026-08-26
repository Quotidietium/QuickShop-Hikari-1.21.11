package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.localization.text.Text;
import com.ghostchu.quickshop.api.localization.text.TextManager;
import com.ghostchu.quickshop.api.shop.IShopType;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.util.Util;
import dev.dejvokep.boostedyaml.YamlDocument;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R25 sign-render pipeline: the default template's header and
 * trading lines consult the SAME inventory quantity (selling: remaining stock, buying:
 * remaining space), so one scan must serve both lines; third-party shop types (whose
 * remainingStock may diverge from what Shop#inventoryAvailable consults) keep the
 * independent evaluations. Also guards the layout-template config cache and the
 * renderItem flag snapshot, both refreshed on reload.
 */
class SimpleShopLayoutProviderTest {

  private org.mockito.MockedStatic<Bukkit> bukkitStatic;
  private org.mockito.MockedStatic<QuickShop> quickShopStatic;
  private org.mockito.MockedStatic<Util> utilStatic;
  private QuickShop plugin;
  private YamlDocument config;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();
  private SimpleShopLayoutProvider provider;
  private ProxiedLocale locale;
  private Shop shop;
  private final AtomicInteger stockScans = new AtomicInteger();
  private final AtomicInteger spaceScans = new AtomicInteger();
  private final AtomicInteger stockValue = new AtomicInteger(64);
  private final AtomicInteger spaceValue = new AtomicInteger(27);
  private final AtomicBoolean availableValue = new AtomicBoolean(true);

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    utilStatic = mockStatic(Util.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));

    final ShopManager shopManager = mock(ShopManager.class);
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);
    lenient().when(shopManager.format(anyDouble(), any(Shop.class))).thenReturn("$12.5");

    config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getBoolean(anyString())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Boolean bool && bool;
    });
    lenient().when(config.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      if(value instanceof final Boolean bool) {
        return bool;
      }
      return inv.getArgument(1, Boolean.class);
    });
    lenient().when(config.getString(anyString())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final String str? str : null;
    });
    lenient().when(config.getString(anyString(), any(String.class))).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      if(value instanceof final String str) {
        return str;
      }
      return inv.getArgument(1, String.class);
    });
    lenient().when(plugin.getConfig()).thenReturn(config);

    // text pipeline: every lookup becomes a plain-text marker carrying the translation
    // key and the rendered arguments, so assertions can see exactly which key each
    // line rendered with
    final TextManager text = mock(TextManager.class);
    lenient().when(plugin.text()).thenReturn(text);
    lenient().when(text.of(anyString(), any(Object[].class))).thenAnswer(inv -> {
      // Mockito hands varargs over expanded: [path, arg1, arg2, ...]
      final Object[] raw = inv.getArguments();
      final String path = (String)raw[0];
      final StringBuilder marker = new StringBuilder(path).append('[');
      for(int i = 1; i < raw.length; i++) {
        if(i > 1) {
          marker.append(", ");
        }
        marker.append(argText(raw[i]));
      }
      marker.append(']');
      final Text textResult = mock(Text.class);
      final Component component = Component.text(marker.toString());
      lenient().when(textResult.forLocale(anyString())).thenReturn(component);
      lenient().when(textResult.forLocale()).thenReturn(component);
      return textResult;
    });

    // renderItem's name resolution goes through Util.getItemStackName; stubbed here so
    // the flag-snapshot test observes branch selection without the platform statics
    utilStatic.when(() -> Util.getItemStackName(any(org.bukkit.inventory.ItemStack.class)))
            .thenReturn(Component.text("item-name"));

    provider = new SimpleShopLayoutProvider(plugin);

    locale = mock(ProxiedLocale.class);
    lenient().when(locale.getLocale()).thenReturn("en_us");

    shop = mock(Shop.class);
    lenient().when(shop.shopType()).thenReturn(SimpleShopManager.SELLING_TYPE);
    lenient().when(shop.shopState()).thenReturn(SimpleShopManager.ACTIVE_STATE);
    lenient().when(shop.isUnlimited()).thenReturn(false);
    lenient().when(shop.isStackingShop()).thenReturn(false);
    lenient().when(shop.getPrice()).thenReturn(12.5d);
    final var shopItem = mock(org.bukkit.inventory.ItemStack.class);
    lenient().when(shopItem.hasItemMeta()).thenReturn(false);
    lenient().when(shop.getItem()).thenReturn(shopItem);
    lenient().when(shop.ownerName(any(Boolean.class), any())).thenReturn(Component.text("owner"));
    lenient().when(shop.getItemUnitSize()).thenReturn(1);
    lenient().when(shop.matches(any(org.bukkit.inventory.ItemStack.class))).thenReturn(true);
    // the availability/quantity answers below read settable values instead of
    // delegating to sibling mock methods: when() stubbing probes execute the previous
    // answer once, and a delegating answer would then nest a mock call mid-stubbing
    // (Mockoto's classic WrongTypeOfReturnValue trap) as well as double-count scans
    lenient().when(shop.inventoryAvailable()).thenAnswer(inv -> availableValue.get());
    lenient().when(shop.getRemainingStock()).thenAnswer(inv -> {
      stockScans.incrementAndGet();
      return stockValue.get();
    });
    lenient().when(shop.getRemainingSpace()).thenAnswer(inv -> {
      spaceScans.incrementAndGet();
      return spaceValue.get();
    });
  }

  @AfterEach
  void tearDown() {

    utilStatic.close();
    quickShopStatic.close();
    bukkitStatic.close();
  }

  private static String argText(final Object arg) {

    if(arg instanceof final TextComponent text) {
      return text.content();
    }
    return String.valueOf(arg);
  }

  private static String content(final Component component) {

    assertTrue(component instanceof TextComponent, "marker components are plain text");
    return ((TextComponent)component).content();
  }

  @Test
  void defaultTemplateScansInventoryOnceForHeaderAndTrading() {

    final List<Component> lines = provider.render(shop, locale);

    assertEquals(4, lines.size());
    assertEquals(1, stockScans.get(), "header + trading must share ONE inventory scan");
    assertEquals("signs.header-available[owner]", content(lines.get(0)));
    assertEquals("signs.selling[64]", content(lines.get(1)));
  }

  @Test
  void renderMatchesDirectLineComposition() {

    final List<Component> composed = provider.render(shop, locale);
    assertEquals(content(provider.renderHeader(shop, locale)), content(composed.get(0)));
    assertEquals(content(provider.renderTrading(shop, locale)), content(composed.get(1)));
  }

  @Test
  void buyingShopScansSpaceOnce() {

    when(shop.shopType()).thenReturn(SimpleShopManager.BUYING_TYPE);
    spaceValue.set(0);
    availableValue.set(false);

    final List<Component> lines = provider.render(shop, locale);

    assertEquals(1, spaceScans.get(), "buying header + trading must share ONE space scan");
    verify(shop, never()).getRemainingStock();
    assertEquals("signs.header-unavailable[owner]", content(lines.get(0)));
    assertEquals("signs.out-of-space[]", content(lines.get(1)));
  }

  @Test
  void thirdPartyTypeKeepsIndependentLookups() {

    final IShopType custom = mock(IShopType.class);
    when(custom.identifier()).thenReturn("CUSTOM");
    when(custom.isBuying()).thenReturn(false);
    when(custom.remainingStock(any(Shop.class))).thenReturn(5);
    when(custom.tradingTranslationKey()).thenReturn("signs.custom-trading");
    when(custom.stackTradingTranslationKey()).thenReturn("signs.custom-stack");
    when(custom.outOfStockTranslationKey()).thenReturn("signs.custom-empty");
    when(shop.shopType()).thenReturn(custom);
    // header consults inventoryAvailable (false), trading consults the type (5):
    // the two must stay independent for types outside the built-in pair
    when(shop.inventoryAvailable()).thenReturn(false);
    // the type's remaining count (5) deliberately diverges from the shop's stock
    // (999): both lookups must stay independent for types outside the built-in pair
    stockValue.set(999);

    final List<Component> lines = provider.render(shop, locale);

    assertEquals("signs.header-unavailable[owner]", content(lines.get(0)));
    assertEquals("signs.custom-trading[5]", content(lines.get(1)));
    verify(shop, never()).getRemainingStock();
  }

  @Test
  void unlimitedShopRendersAvailableHeaderAndUnlimitedTrading() {

    when(shop.isUnlimited()).thenReturn(true);
    stockValue.set(-1);

    final List<Component> lines = provider.render(shop, locale);

    assertEquals("signs.header-available[owner]", content(lines.get(0)));
    assertEquals("signs.selling[signs.unlimited[]]", content(lines.get(1)));
    assertEquals(1, stockScans.get(), "unlimited is a fast-path value, still resolved once");
  }

  @Test
  void templateWithoutHeaderOrTradingNeverScans() {

    configValues.put("shop.layout.SELLING.line1", "item");
    configValues.put("shop.layout.SELLING.line2", "price");
    configValues.put("shop.layout.SELLING.line3", "");
    configValues.put("shop.layout.SELLING.line4", "price");
    provider.reloadModule();

    provider.render(shop, locale);

    verify(shop, never()).getRemainingStock();
    verify(shop, never()).inventoryAvailable();
  }

  @Test
  void layoutTemplateIsCachedPerShopTypeUntilReload() {

    configValues.put("shop.layout.SELLING.line1", "header");
    provider.reloadModule();

    provider.layoutTemplate(shop);
    provider.layoutTemplate(shop);
    verify(config, times(1)).getString("shop.layout.SELLING.line1", "header");

    configValues.put("shop.layout.SELLING.line1", "price");
    provider.reloadModule();
    assertEquals("price", provider.layoutTemplate(shop).get(0),
                 "reload must drop the cached template");

    verify(config, times(2)).getString("shop.layout.SELLING.line1", "header");
  }

  @Test
  void renderItemFlagSnapshotFollowsReload() {

    final var item = mock(org.bukkit.inventory.ItemStack.class);
    final var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
    lenient().when(item.hasItemMeta()).thenReturn(true);
    lenient().when(item.getItemMeta()).thenReturn(meta);
    lenient().when(meta.hasDisplayName()).thenReturn(true);
    lenient().when(shop.getItem()).thenReturn(item);

    configValues.put("shop.force-use-item-original-name", true);
    provider.reloadModule();

    provider.renderItem(shop, locale);
    // snapshot=true short-circuits the condition, the item's meta is never inspected
    verify(item, never()).hasItemMeta();

    configValues.put("shop.force-use-item-original-name", false);
    provider.reloadModule();

    provider.renderItem(shop, locale);
    // snapshot=false: the condition falls through to the meta inspection
    verify(item, times(1)).hasItemMeta();
  }
}
