package com.ghostchu.quickshop.shop.display;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.display.DisplayType;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R32 display-path snapshots: the backend type, the guard-item
 * name flag and the spawn-offset coordinates were fresh config-tree walks on every
 * guard-check/spawn/display-placement; they now live in static volatiles refreshed by
 * the {@code Util.initialize()} hook. Staleness-until-refresh must remain observable,
 * proving no hidden live read survives.
 */
class AbstractDisplayItemSnapshotTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<com.ghostchu.quickshop.util.Util> utilStatic;
  private QuickShop plugin;
  private final Map<String, Object> configValues = new ConcurrentHashMap<>();

  /** The instance earlier test classes may have bound into AbstractDisplayItem.PLUGIN. */
  private QuickShop captured;

  @BeforeEach
  void setUp() throws Exception {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    utilStatic = mockStatic(com.ghostchu.quickshop.util.Util.class);
    utilStatic.when(() -> com.ghostchu.quickshop.util.Util.serialize(any(ItemStack.class)))
            .thenReturn("{}");
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    // AbstractDisplayItem's class initializer builds a NamespacedKey from
    // getJavaPlugin().getName() — both must answer before the first touch
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");

    final var field = AbstractDisplayItem.class.getDeclaredField("PLUGIN");
    field.setAccessible(true);
    captured = (QuickShop)field.get(null);

    final YamlDocument config = mock(YamlDocument.class);
    configValues.clear();
    lenient().when(config.getInt(anyString())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Integer integer ? integer : 0;
    });
    lenient().when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      return value instanceof final Integer integer ? integer : inv.getArgument(1, Integer.class);
    });
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
    lenient().when(config.getDouble(anyString(), anyDouble())).thenAnswer(inv -> {
      final Object value = configValues.get(inv.getArgument(0, String.class));
      if(value instanceof final Double d) {
        return d;
      }
      return inv.getArgument(1, Double.class);
    });

    for(final QuickShop target : new QuickShop[]{plugin, captured}) {
      if(target == null) {
        continue;
      }
      lenient().when(target.getConfig()).thenReturn(config);
      lenient().when(target.getReloadManager())
              .thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
      lenient().when(target.isDisplayEnabled()).thenReturn(true);
    }
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
    utilStatic.close();
  }

  private void put(final String key, final Object value) {

    configValues.put(key, value);
  }

  private Shop shopAt(final double x, final double y, final double z) {

    final Shop shop = mock(Shop.class);
    lenient().when(shop.bukkitLocation()).thenReturn(new Location(null, x, y, z));
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);
    lenient().when(shop.getItem()).thenReturn(item);
    return shop;
  }

  /** Trivial concrete display used only to reach inherited behavior. */
  private static final class TestDisplay extends AbstractDisplayItem {

    TestDisplay(final Shop shop) {

      super(shop);
    }

    @Override
    public boolean checkDisplayIsMoved() {

      return false;
    }

    @Override
    public boolean checkDisplayNeedRegen() {

      return false;
    }

    @Override
    public boolean checkIsShopEntity(org.bukkit.entity.Entity entity) {

      return false;
    }

    @Override
    public void fixDisplayMoved() {

    }

    @Override
    public void fixDisplayNeedRegen() {

    }

    @Override
    public Entity getDisplay() {

      return null;
    }

    @Override
    public boolean isSpawned() {

      return false;
    }

    @Override
    public boolean isApplicableForPlayer(Player player) {

      return false;
    }

    @Override
    public void remove(boolean dontTouchWorld) {

    }

    @Override
    public boolean removeDupe() {

      return false;
    }

    @Override
    public void respawn() {

    }

    @Override
    public void spawn() {

    }

    @Override
    public void safeGuard(@NotNull org.bukkit.entity.Entity entity) {

    }
  }

  private record GuardStack(ItemStack stack, ItemMeta meta) {
  }

  private GuardStack metaStack() {

    final ItemStack stack = mock(ItemStack.class);
    lenient().when(stack.hasItemMeta()).thenReturn(true);
    lenient().when(stack.clone()).thenReturn(stack);
    final ItemMeta meta = mock(ItemMeta.class);
    lenient().when(stack.getItemMeta()).thenReturn(meta);
    lenient().when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    return new GuardStack(stack, meta);
  }

  @Test
  void backendTypeStaysSnappedUntilRefreshReplacesIt() {

    put("shop.display-type", DisplayType.VIRTUALITEM.toID());
    AbstractDisplayItem.refreshConfigSnapshots();
    assertEquals(DisplayType.VIRTUALITEM, AbstractDisplayItem.getNowUsing());

    put("shop.display-type", DisplayType.CUSTOM.toID());
    assertEquals(DisplayType.VIRTUALITEM, AbstractDisplayItem.getNowUsing(),
                 "a mutated config must not leak through the snapshot");

    AbstractDisplayItem.refreshConfigSnapshots();
    assertEquals(DisplayType.CUSTOM, AbstractDisplayItem.getNowUsing());
  }

  @Test
  void guardSweepGateMirrorsBackendAndEnabledFlags() {

    put("shop.display-type", DisplayType.CUSTOM.toID());
    AbstractDisplayItem.refreshConfigSnapshots();
    assertTrue(AbstractDisplayItem.canProduceGuardItems(),
               "real/custom backends do produce guard stacks");

    put("shop.display-type", DisplayType.VIRTUALITEM.toID());
    AbstractDisplayItem.refreshConfigSnapshots();
    assertFalse(AbstractDisplayItem.canProduceGuardItems(),
                "the virtual backend never produces guard stacks");
    assertFalse(AbstractDisplayItem.checkIsGuardItemStack(mock(ItemStack.class)));
  }

  @Test
  void guardStackNameFlagGovernsTheDisplayNameSideEffect() {

    put("shop.display-type", DisplayType.CUSTOM.toID());
    // absent == false: names are stripped by default (original one-arg semantics)
    AbstractDisplayItem.refreshConfigSnapshots();

    final GuardStack stripped = metaStack();
    AbstractDisplayItem.createGuardItemStack(stripped.stack(), shopAt(0, 0, 0));
    verify(stripped.meta()).setDisplayName(null);

    put("shop.display-item-use-name", Boolean.TRUE);
    AbstractDisplayItem.refreshConfigSnapshots();

    final GuardStack kept = metaStack();
    AbstractDisplayItem.createGuardItemStack(kept.stack(), shopAt(0, 0, 0));
    verify(kept.meta(), never()).setDisplayName(any());
  }

  @Test
  void displayLocationCoordinatesStaySnappedUntilRefresh() {

    put("shop.display-coords.x", 0.25d);
    put("shop.display-coords.y", 0.75d);
    put("shop.display-coords.z", 0.33d);
    AbstractDisplayItem.refreshConfigSnapshots();

    final TestDisplay display = new TestDisplay(shopAt(10, 20, 30));
    Location location = display.getDisplayLocation();
    assertEquals(10.25d, location.getX(), 1e-9);
    assertEquals(20.75d, location.getY(), 1e-9);
    assertEquals(30.33d, location.getZ(), 1e-9);

    put("shop.display-coords.x", 1.0d);
    put("shop.display-coords.y", 1.0d);
    put("shop.display-coords.z", 1.0d);
    location = display.getDisplayLocation();
    assertEquals(10.25d, location.getX(), 1e-9, "staleness must be observable pre-refresh");

    AbstractDisplayItem.refreshConfigSnapshots();
    location = display.getDisplayLocation();
    assertEquals(11.0d, location.getX(), 1e-9);
    assertEquals(21.0d, location.getY(), 1e-9);
    assertEquals(31.0d, location.getZ(), 1e-9);
  }
}
