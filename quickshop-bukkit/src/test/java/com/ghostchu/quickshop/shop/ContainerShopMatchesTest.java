package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.event.AbstractQSEvent;
import com.ghostchu.quickshop.api.event.general.ShopItemMatchEvent;
import com.ghostchu.quickshop.util.matcher.item.QuickShopItemMatcherImpl;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the per-slot shop matching fast path: the builtin matcher receives
 * the live stacks (it normalizes internally and never mutates them), while third-party
 * matchers still get amount-normalized defensive copies; ShopItemMatchEvent construction
 * is skipped when no listener is registered but still reaches listeners when one is.
 */
class ContainerShopMatchesTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private org.bukkit.World world;
  private PluginManager pluginManager;

  @BeforeEach
  void setUp() throws java.io.IOException {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    pluginManager = mock(PluginManager.class);
    bukkitStatic.when(Bukkit::isPrimaryThread).thenReturn(true);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    HandlerList.unregisterAll();

    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    // re-stub after MockBukkit.install overwrote it, so this suite controls dispatch
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-matches-test").toFile());
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(any(String.class))).thenReturn(false);
    lenient().when(config.getInt(org.mockito.ArgumentMatchers.anyString())).thenReturn(0);
    lenient().when(config.getSection("matcher.item"))
            .thenReturn(mock(dev.dejvokep.boostedyaml.block.implementation.Section.class));
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.platform()).thenReturn(mock(com.ghostchu.quickshop.platform.Platform.class));

    world = mock(org.bukkit.World.class);
    when(world.getName()).thenReturn("world");
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    bukkitStatic.close();
    quickShopStatic.close();
  }

  private ContainerShop shop(final ItemStack item) {

    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("match-owner".getBytes()), "match-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    return new ContainerShop(
            plugin, -1L, new org.bukkit.Location(world, 1, 64, 1), 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), null, false, null,
            "Bukkit", "sym-matches", null,
            new HashMap<>(), benefit);
  }

  private static ItemStack slot(final Material material) {

    final ItemStack stack = mock(ItemStack.class);
    lenient().when(stack.getType()).thenReturn(material);
    lenient().when(stack.getAmount()).thenReturn(17);
    lenient().when(stack.hasItemMeta()).thenReturn(false);
    lenient().when(stack.clone()).thenReturn(stack);
    return stack;
  }

  /**
   * Mock stack whose amount tracks setAmount and whose clone() recursively yields another
   * stateful copy — mirrors real ItemStack semantics closely enough to observe (and rule
   * out) cross-object mutations.
   */
  private static ItemStack[] statefulItem(final Material material, final int amount) {

    final ItemStack stack = mock(ItemStack.class);
    final int[] amt = {amount};
    lenient().when(stack.getType()).thenReturn(material);
    lenient().when(stack.getAmount()).thenAnswer(inv -> amt[0]);
    lenient().when(stack.hasItemMeta()).thenReturn(false);
    lenient().when(stack.isSimilar(any(ItemStack.class))).thenAnswer(
            inv -> inv.getArgument(0, ItemStack.class).getType() == material);
    when(stack.clone()).thenAnswer(inv -> {
      final ItemStack[] copy = statefulItem(material, amt[0]);
      return copy[0];
    });
    doAnswer(inv -> {
      amt[0] = inv.getArgument(0, Integer.class);
      return null;
    }).when(stack).setAmount(anyInt());
    return new ItemStack[]{stack, null};// caller reads the amount through getAmount()
  }

  @Test
  void builtinMatcherMatchesWithoutMutatingInputs() {

    final QuickShopItemMatcherImpl matcher = new QuickShopItemMatcherImpl(plugin);
    when(plugin.getItemMatcher()).thenReturn(matcher);

    final ItemStack shopItem = statefulItem(Material.DIAMOND, 64)[0];
    final ContainerShop shop = shop(shopItem);

    final ItemStack matching = statefulItem(Material.DIAMOND, 17)[0];
    final ItemStack foreign = slot(Material.IRON_INGOT);

    assertTrue(shop.matches(matching));
    assertFalse(shop.matches(foreign));
    assertFalse(shop.matches(null));

    // neither the scanned stack nor the shop item may be mutated by the fast path
    // (the builtin matcher only mutates its own internal clones)
    assertEquals(17, matching.getAmount());
    assertEquals(64, shopItem.getAmount());
  }

  @Test
  void thirdPartyMatcherReceivesNormalizedDefensiveCopies() {

    final AtomicReference<ItemStack> seenRequire = new AtomicReference<>();
    final AtomicReference<ItemStack> seenGiven = new AtomicReference<>();
    final java.util.concurrent.atomic.AtomicInteger givenAmountOnEntry = new java.util.concurrent.atomic.AtomicInteger(-1);
    final java.util.concurrent.atomic.AtomicInteger requireAmountOnEntry = new java.util.concurrent.atomic.AtomicInteger(-1);
    final com.ghostchu.quickshop.api.shop.ItemMatcher thirdParty = new com.ghostchu.quickshop.api.shop.ItemMatcher() {
      @Override
      public boolean matches(@Nullable final ItemStack requireStack, @Nullable final ItemStack givenStack) {

        if(requireStack != null) {
          requireAmountOnEntry.set(requireStack.getAmount());
          requireStack.setAmount(999);// hostile mutation attempt
        }
        if(givenStack != null) {
          givenAmountOnEntry.set(givenStack.getAmount());
        }
        seenRequire.set(requireStack);
        seenGiven.set(givenStack);
        return true;
      }

      @Override
      public @NotNull String getName() {

        return "third-party";
      }

      @Override
      public @NotNull Plugin getPlugin() {

        return mock(Plugin.class);
      }
    };
    when(plugin.getItemMatcher()).thenReturn(thirdParty);

    final ItemStack shopItem = statefulItem(Material.DIAMOND, 64)[0];
    final ContainerShop shop = shop(shopItem);

    final ItemStack scanned = statefulItem(Material.DIAMOND, 17)[0];

    assertTrue(shop.matches(scanned));

    // matcher saw amount-normalized stacks ...
    assertEquals(1, requireAmountOnEntry.get());
    assertEquals(1, givenAmountOnEntry.get());
    // ... and its mutation attempt never reached the originals
    assertEquals(64, shopItem.getAmount());
    assertEquals(17, scanned.getAmount());
  }

  @Test
  void matchEventStillReachesRegisteredListeners() {

    final RegisteredListener listener = new RegisteredListener(
            mock(Listener.class),
            (executorListener, event)->((ShopItemMatchEvent)event).matches(true),
            EventPriority.NORMAL,
            mock(Plugin.class),
            false);
    AbstractQSEvent.getHandlerList().register(listener);
    // the mocked PluginManager does not run executors; call it directly like the server would
    doAnswer(inv -> {
      listener.callEvent(inv.getArgument(0, org.bukkit.event.Event.class));
      return null;
    }).when(pluginManager).callEvent(any(org.bukkit.event.Event.class));

    final QuickShopItemMatcherImpl matcher = new QuickShopItemMatcherImpl(plugin);
    when(plugin.getItemMatcher()).thenReturn(matcher);

    // dissimilar stacks: only the event listener can turn this into a match
    final ItemStack require = slot(Material.DIAMOND);
    when(require.isSimilar(any(ItemStack.class))).thenReturn(false);
    final ItemStack given = slot(Material.IRON_INGOT);

    assertTrue(matcher.matches(require, given));
  }

  @Test
  void noListenersGivesPlainMismatchResult() {

    assertEquals(0, AbstractQSEvent.getHandlerList().getRegisteredListeners().length);

    final QuickShopItemMatcherImpl matcher = new QuickShopItemMatcherImpl(plugin);
    when(plugin.getItemMatcher()).thenReturn(matcher);

    final ItemStack require = slot(Material.DIAMOND);
    when(require.isSimilar(any(ItemStack.class))).thenReturn(false);
    final ItemStack given = slot(Material.IRON_INGOT);

    // type mismatch dominates every builtin work-type path
    assertFalse(matcher.matches(require, given));
  }
}
