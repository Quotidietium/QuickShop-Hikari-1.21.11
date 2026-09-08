package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.QuickShopProvider;
import com.ghostchu.quickshop.api.shop.interaction.InteractionBehavior;
import com.ghostchu.quickshop.api.shop.interaction.InteractionClick;
import com.ghostchu.quickshop.api.shop.interaction.InteractionManager;
import com.ghostchu.quickshop.api.shop.interaction.InteractionType;
import com.ghostchu.quickshop.shop.interaction.QuickShopInteractionManager;
import com.ghostchu.quickshop.shop.interaction.behaviors.ControlPanel;
import com.ghostchu.quickshop.shop.interaction.behaviors.TradeInteraction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Regression tests for the click-path interaction dispatch: PlayerListener resolves the
 * interaction type and its mapped behavior on every player block interact. The OrNull
 * resolution path must agree with the historic Optional-returning API, resolve the same
 * behavior instance across calls (identifier lowercase memoization), and keep the NONE
 * placeholder resolving to "no behavior" exactly like an unmapped interaction.
 */
class InteractionDispatchTest {

  private static final String INTERACTION_YAML = """
          version: 3
          STANDING_LEFT_CLICK_SHOPBLOCK: TRADE_INTERACTION
          STANDING_RIGHT_CLICK_SIGN: CONTROL_PANEL
          STANDING_RIGHT_CLICK_SHOPBLOCK: NONE
          """;

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;
  private QuickShopInteractionManager manager;
  private PlayerInteractEvent leftClick;
  private PlayerInteractEvent rightClick;

  @BeforeEach
  void setUp() throws Exception {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    lenient().when(plugin.getPasteManager()).thenReturn(mock(com.ghostchu.quickshop.util.paste.PasteManager.class));

    // QSConfig resolves the data folder and the bundled default resource through
    // QuickShopAPI.getPluginInstance(); wire the services chain to a bukkit-plugin mock
    // carrying a real temp folder with a real interaction.yml (the production load path)
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    final Path dataFolder = Files.createTempDirectory("qs-interaction-dispatch");
    Files.write(dataFolder.resolve("interaction.yml"), INTERACTION_YAML.getBytes(StandardCharsets.UTF_8));
    lenient().when(bukkitPlugin.getDataFolder()).thenReturn(dataFolder.toFile());
    lenient().when(bukkitPlugin.getResource(anyString()))
            .thenAnswer(inv -> new java.io.ByteArrayInputStream(
                    INTERACTION_YAML.getBytes(StandardCharsets.UTF_8)));

    final QuickShopProvider provider = mock(QuickShopProvider.class);
    lenient().when(provider.getApiInstance()).thenReturn(plugin);
    lenient().when(provider.getInstance()).thenReturn(bukkitPlugin);
    final RegisteredServiceProvider<QuickShopProvider> rsp = mock(RegisteredServiceProvider.class);
    lenient().when(rsp.getProvider()).thenReturn(provider);
    lenient().when(rsp.getPlugin()).thenReturn(bukkitPlugin);
    final ServicesManager servicesManager = mock(ServicesManager.class);
    lenient().when(servicesManager.getRegistration(QuickShopProvider.class)).thenReturn(rsp);
    bukkitStatic.when(Bukkit::getServicesManager).thenReturn(servicesManager);
    lenient().when(Bukkit.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));

    manager = new QuickShopInteractionManager(plugin);

    final Player player = mock(Player.class);
    lenient().when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
    lenient().when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(new byte[]{3}));
    lenient().when(player.isSneaking()).thenReturn(false);
    // the standing left-click predicates keep the golden-axe exception (axe in hand
    // breaks the shop instead of trading), so they consult the main hand
    final var mainHand = mock(org.bukkit.inventory.ItemStack.class);
    lenient().when(mainHand.getType()).thenReturn(org.bukkit.Material.AIR);
    final var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
    lenient().when(inventory.getItemInMainHand()).thenReturn(mainHand);
    lenient().when(player.getInventory()).thenReturn(inventory);

    leftClick = interactEvent(player, Action.LEFT_CLICK_BLOCK);
    rightClick = interactEvent(player, Action.RIGHT_CLICK_BLOCK);
  }

  /** PlayerEvent.getPlayer() is final: inject the protected field instead of stubbing. */
  private PlayerInteractEvent interactEvent(final Player player, final Action action) throws Exception {

    final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    lenient().when(event.getAction()).thenReturn(action);
    lenient().when(event.getPlayer()).thenReturn(player);
    final Field playerField = org.bukkit.event.player.PlayerEvent.class.getDeclaredField("player");
    playerField.setAccessible(true);
    playerField.set(event, player);
    return event;
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  @Test
  void standingLeftPunchResolvesTheMappedTradeBehavior() {

    final InteractionType type = manager.interactionOrNull(leftClick, InteractionClick.SHOPBLOCK);
    assertEquals("STANDING_LEFT_CLICK_SHOPBLOCK", type.identifier());

    final InteractionBehavior behavior = manager.behaviorOrNull(type);
    assertTrue(behavior instanceof TradeInteraction, "TRADE_INTERACTION mapping must resolve");
    // memoized resolution: repeated dispatches return the same registered instance
    assertSame(behavior, manager.behaviorOrNull(type));
    assertSame(behavior, manager.behavior(type).orElseThrow());
  }

  @Test
  void noneMappingResolvesToNoBehavior() {

    final InteractionType type = manager.interactionOrNull(rightClick, InteractionClick.SHOPBLOCK);
    assertEquals("STANDING_RIGHT_CLICK_SHOPBLOCK", type.identifier());

    assertNull(manager.behaviorOrNull(type), "NONE must resolve to no behavior");
    assertTrue(manager.behavior(type).isEmpty(), "Optional variant agrees on NONE");
  }

  @Test
  void standingRightSignResolvesControlPanel() {

    final InteractionType type = manager.interactionOrNull(rightClick, InteractionClick.SIGN);
    assertEquals("STANDING_RIGHT_CLICK_SIGN", type.identifier());
    assertTrue(manager.behaviorOrNull(type) instanceof ControlPanel);
  }

  @Test
  void orNullResolutionAgreesWithTheOptionalApi() {

    assertSame(manager.interactionOrNull(leftClick, InteractionClick.SHOPBLOCK),
               manager.interaction(leftClick, InteractionClick.SHOPBLOCK).orElse(null));
    assertNull(manager.interactionOrNull(rightClick, InteractionClick.AIR),
               "AIR clicks match no registered interaction");
    assertTrue(manager.interaction(rightClick, InteractionClick.AIR).isEmpty());
  }

  @Test
  void interfaceDefaultsDelegateToTheOptionalVariants() {

    final InteractionManager base = mock(InteractionManager.class,
            withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
    final InteractionType type = manager.interactionOrNull(leftClick, InteractionClick.SHOPBLOCK);
    final InteractionBehavior behavior = manager.behaviorOrNull(type);
    when(base.interaction(any(PlayerInteractEvent.class), any(InteractionClick.class)))
            .thenReturn(Optional.of(type));
    when(base.behavior(any(InteractionType.class))).thenReturn(Optional.of(behavior));

    assertSame(type, base.interactionOrNull(leftClick, InteractionClick.SHOPBLOCK));
    assertSame(behavior, base.behaviorOrNull(type));

    when(base.interaction(any(PlayerInteractEvent.class), any(InteractionClick.class)))
            .thenReturn(Optional.empty());
    when(base.behavior(any(InteractionType.class))).thenReturn(Optional.empty());
    assertNull(base.interactionOrNull(leftClick, InteractionClick.SHOPBLOCK));
    assertNull(base.behaviorOrNull(type));
  }
}
