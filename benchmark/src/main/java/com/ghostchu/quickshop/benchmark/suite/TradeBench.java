package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperRegistry;
import com.ghostchu.quickshop.benchmark.Env;
import com.ghostchu.quickshop.obj.QUserImpl;
import com.ghostchu.quickshop.shop.ContainerShop;
import com.ghostchu.quickshop.shop.SimpleInventoryTransaction;
import com.ghostchu.quickshop.shop.SimpleShopLayoutProvider;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import com.ghostchu.quickshop.shop.SimpleTradeService;
import com.ghostchu.quickshop.shop.inventory.BukkitInventoryWrapper;
import com.ghostchu.quickshop.shop.inventory.BukkitInventoryWrapperManager;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.matcher.item.QuickShopItemMatcherImpl;
import com.ghostchu.quickshop.watcher.SignUpdateWatcher;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures the per-trade inventory hot path: shop-item matching over full inventory scans
 * (preview checks), the two-sided inventory transaction commit (remove-from-chest +
 * add-to-player, including operation logging and rollback snapshots), and the full
 * trade-service buy path (validation, preview, commit, post-trade sign refresh).
 * <p>
 * The mock {@link Inventory} mirrors real CraftInventory semantics: every
 * {@code getStorageContents()} call returns a fresh array copy, so the iterator behaviour
 * (array copies per iteration) matches production. Mockito item stacks stand in for real
 * ItemStacks; their clone() cost is therefore UNDER-estimated (stubbed to return this) —
 * real-world clone-related wins are larger than what these cases can show. The sign-render
 * case runs against a mocked text pipeline, so it measures the structural cost (scheduling,
 * folia dispatch, layout provider, block writes) rather than MiniMessage parsing, which the
 * text suite covers separately.
 */
public final class TradeBench {

  private static final int CHEST_SLOTS = 54;
  private static final int PLAYER_SLOTS = 41;
  private static final int CHEST_SHOP_ITEM_SLOTS = 18;
  private static final int PLAYER_SHOP_ITEM_SLOTS = 10;

  private TradeBench() {

  }

  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) {

    final Fixtures fixtures = setup();
    installTradeServiceEnvironment(fixtures);

    // diagnostic: pure iteration over the mock inventory (no matching) to attribute
    // scan cost between the iterator/array-copy layer and the matcher layer
    harness.bench("trade/iterateOnly54", ctx -> {
      int seen = 0;
      for(final ItemStack ignored : fixtures.chest()) {
        seen++;
      }
      consume(seen);
    });

    harness.bench("trade/countItemsScan54", ctx -> {
      ctx.index++;
      consume(Util.countItems(fixtures.chest(), fixtures.shop()));
    });

    harness.bench("trade/countSpaceScan41", ctx -> {
      ctx.index++;
      consume(Util.countSpace(fixtures.player(), fixtures.shop()));
    });

    final AtomicInteger txCounter = new AtomicInteger();
    harness.bench("trade/inventoryTxCommit", ctx -> {
      ctx.index++;
      // fresh stateful item per op: operation code mutates amounts (normalize/remove
      // accounting), and the shared shop item must stay pristine for the scan cases
      final SimpleInventoryTransaction tx = SimpleInventoryTransaction.builder()
              .from(fixtures.chest())
              .to(fixtures.player())
              .item(statefulItem(Material.DIAMOND, 64, true))
              .amount(64)
              .build();
      final boolean result = tx.failSafeCommit();
      if(!result) {
        throw new IllegalStateException("benchmark inventory transaction must succeed, run #" + txCounter.incrementAndGet() + ", lastError=" + tx.getLastError());
      }
      consume(result);
    });

    final SimpleTradeService tradeService = new SimpleTradeService(Env.plugin());
    final com.ghostchu.quickshop.api.obj.QUser buyer = QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("trade-buyer".getBytes(StandardCharsets.UTF_8)), "trade-buyer", true);
    final AtomicInteger serviceCounter = new AtomicInteger();
    harness.bench("trade/tradeServiceBuy", ctx -> {
      ctx.index++;
      final var result = tradeService.executeBuyFromShop(
              fixtures.tradeShop(), buyer, fixtures.player(),
              new Location(fixtures.world(), 1000, 64, 1000), 1);
      if(!result.success()) {
        throw new IllegalStateException("benchmark trade must succeed, run #" + serviceCounter.incrementAndGet()
                + ", reason=" + result.failureReason() + " (" + result.debugMessage() + ")");
      }
      consume(result);
    });
  }

  /** Fixtures shared by the bench cases. */
  record Fixtures(InventoryWrapper chest, InventoryWrapper player, ContainerShop shop,
                  ItemStack shopItem, Inventory chestInventory, World world, ContainerShop tradeShop) {

  }

  private static Fixtures setup() {

    final QuickShop plugin = Env.plugin();

    // real production matcher (workType 0, empty meta-matcher config -> defaults)
    final Section matcherConfig = mock(Section.class);
    when(plugin.getConfig().getSection("matcher.item")).thenReturn(matcherConfig);
    final com.ghostchu.quickshop.platform.Platform platform =
            mock(com.ghostchu.quickshop.platform.Platform.class);
    when(platform.getItemShopId(any(ItemStack.class))).thenReturn(null);
    lenient().when(plugin.platform()).thenReturn(platform);
    final QuickShopItemMatcherImpl matcher = new QuickShopItemMatcherImpl(plugin);
    when(plugin.getItemMatcher()).thenReturn(matcher);

    // constant-amount item for the scan fixtures (cheap clones, never depletes) and a
    // stateful one for the trade-service shop (amount accounting through op clones)
    final ItemStack shopItem = item(Material.DIAMOND, 64, false, true);
    final World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    final ContainerShop shop = createShop(plugin, shopItem, world);
    final ContainerShop tradeShop = createShop(plugin, statefulItem(Material.DIAMOND, 64, true), world);

    final ItemStack[] chestContents = new ItemStack[CHEST_SLOTS];
    for(int i = 0; i < CHEST_SLOTS; i++) {
      if(i < CHEST_SHOP_ITEM_SLOTS) {
        chestContents[i] = item(Material.DIAMOND, 17, false, true);
      } else {
        chestContents[i] = item(MISC_MATERIALS[i % MISC_MATERIALS.length], 7, true, false);
      }
    }
    final ItemStack[] playerContents = new ItemStack[PLAYER_SLOTS];
    for(int i = 0; i < PLAYER_SLOTS; i++) {
      if(i < PLAYER_SHOP_ITEM_SLOTS) {
        playerContents[i] = item(Material.DIAMOND, 3, false, true);
      } else {
        playerContents[i] = item(MISC_MATERIALS[(i + 3) % MISC_MATERIALS.length], 5, true, false);
      }
    }

    final Inventory chestInventory = mock(Inventory.class);
    // mirror CraftInventory: every accessor call copies the array
    when(chestInventory.getStorageContents()).thenAnswer(inv -> chestContents.clone());
    when(chestInventory.getContents()).thenAnswer(inv -> chestContents.clone());
    when(chestInventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    lenient().when(chestInventory.getHolder(false)).thenReturn(mock(InventoryHolder.class));

    return new Fixtures(new BukkitInventoryWrapper(chestInventory), wrap(playerContents),
            shop, shopItem, chestInventory, world, tradeShop);
  }

  /**
   * Wires everything the full trade-service path touches: inventory-wrapper registry with
   * a resolvable symbol link, shoppable block world, inline folia scheduler, layout
   * provider over a mocked text pipeline, and a real sign-update watcher.
   */
  private static void installTradeServiceEnvironment(final Fixtures fixtures) {

    final QuickShop plugin = Env.plugin();
    final World world = fixtures.world();

    // symbol-link resolution: registry under the plugin name + world lookup + chest state
    final InventoryWrapperRegistry registry = new InventoryWrapperRegistry();
    registry.register(plugin.getJavaPlugin(), new BukkitInventoryWrapperManager());
    when(plugin.getInventoryWrapperRegistry()).thenReturn(registry);
    when(Env.server().getWorld("world")).thenReturn(world);
    final var blockState = mock(org.bukkit.block.BlockState.class,
            org.mockito.Mockito.withSettings().extraInterfaces(InventoryHolder.class));
    when(((InventoryHolder)blockState).getInventory()).thenReturn(fixtures.chestInventory());
    final var block = mock(org.bukkit.block.Block.class);
    when(block.getType()).thenReturn(Material.CHEST);
    when(block.getWorld()).thenReturn(world);
    when(block.getState(false)).thenReturn(blockState);
    lenient().when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
    lenient().when(world.getBlockAt(any(Location.class))).thenReturn(block);
    lenient().when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    // shoppables: Util.initialize() rereads shop-blocks, so restub before re-running it
    when(plugin.getConfig().getStringList("shop-blocks")).thenReturn(java.util.List.of("CHEST"));
    Util.initialize();

    // folia: run region-thread tasks inline on the calling thread.
    // QuickShop.folia() is static and reads the instance field directly, so the mock
    // plugin needs the field injected reflectively (stubbing folia() would not help).
    final var folia = mock(com.tcoded.folialib.FoliaLib.class);
    final var scheduler = mock(com.tcoded.folialib.impl.PlatformScheduler.class);
    lenient().when(folia.getScheduler()).thenReturn(scheduler);
    lenient().when(scheduler.runAtLocation(any(Location.class), any(Consumer.class))).thenAnswer(inv -> {
      final Consumer<?> consumer = inv.getArgument(1, Consumer.class);
      consumer.accept(null);
      return CompletableFuture.completedFuture(null);
    });
    injectField(plugin, "folia", folia);

    // sign rendering over a mocked text pipeline (MiniMessage cost lives in the text suite)
    final var textManager = mock(com.ghostchu.quickshop.api.localization.text.TextManager.class);
    final var text = mock(com.ghostchu.quickshop.api.localization.text.Text.class);
    lenient().when(textManager.of(anyString(), any(Object[].class))).thenReturn(text);
    lenient().when(textManager.of(anyString())).thenReturn(text);
    lenient().when(text.forLocale(anyString())).thenReturn(Component.empty());
    final var proxiedLocale = mock(com.ghostchu.quickshop.api.localization.text.ProxiedLocale.class);
    lenient().when(proxiedLocale.getLocale()).thenReturn("en_us");
    lenient().when(textManager.findRelativeLanguages(any(com.ghostchu.quickshop.api.obj.QUser.class), anyBoolean()))
            .thenReturn(proxiedLocale);
    lenient().when(plugin.text()).thenReturn(textManager);
    lenient().when(plugin.getTextManager()).thenReturn(textManager);

    final var shopManager = mock(SimpleShopManager.class);
    lenient().when(shopManager.shopLayoutProvider()).thenReturn(new SimpleShopLayoutProvider(plugin));
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);

    lenient().when(plugin.getSignUpdateWatcher()).thenReturn(new SignUpdateWatcher());
  }

  private static final Material[] MISC_MATERIALS = {
          Material.IRON_INGOT, Material.GOLD_INGOT, Material.EMERALD,
          Material.REDSTONE, Material.LAPIS_LAZULI, Material.COAL
  };

  private static InventoryWrapper wrap(final ItemStack[] contents) {

    final Inventory inventory = mock(Inventory.class);
    // mirror CraftInventory: every accessor call copies the array
    when(inventory.getStorageContents()).thenAnswer(inv -> contents.clone());
    when(inventory.getContents()).thenAnswer(inv -> contents.clone());
    when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    return new BukkitInventoryWrapper(inventory);
  }

  /**
   * Mock stack with stable semantics: constant amount (mutations are no-ops so benchmark
   * loops never deplete the inventory), self-clone, and isSimilar answering "same family"
   * (DIAMOND without meta) so the matcher exercises both its hit and miss paths.
   */
  private static ItemStack item(final Material material, final int amount, final boolean meta, final boolean shopFamily) {

    final ItemStack stack = mock(ItemStack.class);
    when(stack.getType()).thenReturn(material);
    when(stack.getAmount()).thenReturn(amount);
    when(stack.getMaxStackSize()).thenReturn(64);
    when(stack.hasItemMeta()).thenReturn(meta);
    when(stack.clone()).thenReturn(stack);
    when(stack.isSimilar(any(ItemStack.class))).thenAnswer(inv -> {
      final ItemStack other = inv.getArgument(0, ItemStack.class);
      return other != null && other.getType() == Material.DIAMOND && !other.hasItemMeta();
    });
    return stack;
  }

  /**
   * Mock stack whose amount actually tracks setAmount, and whose clone() produces a fresh
   * stateful copy — required wherever transaction code relies on amount accounting
   * (removeItem leftover bookkeeping). Never used as a shared fixture.
   */
  private static ItemStack statefulItem(final Material material, final int amount, final boolean shopFamily) {

    final ItemStack stack = mock(ItemStack.class);
    final int[] amt = {amount};
    when(stack.getType()).thenReturn(material);
    when(stack.getAmount()).thenAnswer(inv -> amt[0]);
    when(stack.getMaxStackSize()).thenReturn(64);
    when(stack.hasItemMeta()).thenReturn(false);
    when(stack.isSimilar(any(ItemStack.class))).thenAnswer(inv -> {
      final ItemStack other = inv.getArgument(0, ItemStack.class);
      return other != null && other.getType() == Material.DIAMOND && !other.hasItemMeta();
    });
    when(stack.clone()).thenAnswer(inv -> statefulItem(material, amt[0], shopFamily));
    org.mockito.Mockito.doAnswer(inv -> {
      amt[0] = inv.getArgument(0, Integer.class);
      return null;
    }).when(stack).setAmount(org.mockito.ArgumentMatchers.anyInt());
    return stack;
  }

  private static ContainerShop createShop(final QuickShop plugin, final ItemStack item, final World world) {

    final Location location = new Location(world, 1000, 64, 1000);
    final com.ghostchu.quickshop.api.obj.QUser owner = QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("trade-owner".getBytes(StandardCharsets.UTF_8)), "trade-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    when(benefit.serialize()).thenReturn("{}");
    final Map<UUID, String> playerGroup = new HashMap<>();
    playerGroup.put(owner.getUniqueId(), "quickshop.builtin.administrator");
    return new ContainerShop(
            plugin, -1L, location, 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new YamlConfiguration(), null, false, null,
            "QuickShop-Hikari", "2;1000;64;1000;world", null,
            playerGroup, benefit);
  }

  private static void consume(final Object value) {

    com.ghostchu.quickshop.benchmark.BenchHarness.consume(value);
  }

  private static void injectField(final Object target, final String field, final Object value) {

    for(Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        final java.lang.reflect.Field declared = type.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
        return;
      } catch(final NoSuchFieldException ignored) {
        // walk up the hierarchy
      } catch(final ReflectiveOperationException e) {
        throw new IllegalStateException("Failed to inject field " + field, e);
      }
    }
    throw new IllegalStateException("Field " + field + " not found on " + target.getClass());
  }
}
