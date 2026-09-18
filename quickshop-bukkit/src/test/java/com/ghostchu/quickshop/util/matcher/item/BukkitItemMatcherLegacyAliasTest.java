package com.ghostchu.quickshop.util.matcher.item;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.platform.Platform;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for the R60 BukkitItemMatcherImpl hot-path rewrite: the trailing
 * setAmount(1) isSimilar re-check was dead code (isSimilar ignores amounts), the match
 * event is built only when listeners exist, and the legacy shopId alias can no longer
 * turn a cross-material mismatch into a match. Stacks are mocked: the unit environment
 * has no CraftBukkit runtime behind the API ItemStack delegate.
 */
class BukkitItemMatcherLegacyAliasTest {

  private QuickShop plugin;
  private Platform platform;
  private BukkitItemMatcherImpl matcher;

  @BeforeEach
  void setUp() {

    plugin = mock(QuickShop.class);
    platform = mock(Platform.class);
    lenient().when(plugin.platform()).thenReturn(platform);
    matcher = new BukkitItemMatcherImpl(plugin);
  }

  private ItemStack stack(final Material type, final boolean similar) {

    final ItemStack stack = mock(ItemStack.class);
    lenient().when(stack.isSimilar(any(ItemStack.class))).thenReturn(similar);
    lenient().when(stack.getType()).thenReturn(type);
    return stack;
  }

  @Test
  void similarStacksMatchWithoutConsultingPlatform() {

    assertTrue(matcher.matches(stack(Material.STONE, true), stack(Material.STONE, true)));

    verify(platform, never()).getItemShopId(any());
  }

  @Test
  void crossMaterialMismatchNeverConsultsShopIdAlias() {

    // differing materials can never match; the shopId alias read must not even run
    assertFalse(matcher.matches(stack(Material.STONE, false), stack(Material.DIRT, false)));

    verify(platform, never()).getItemShopId(any());
  }

  @Test
  void shopIdAliasStillMatchesSameMaterial() {

    final ItemStack original = stack(Material.STONE, false);
    final ItemStack tester = stack(Material.STONE, false);
    lenient().when(platform.getItemShopId(original)).thenReturn("legacy-tag");
    lenient().when(platform.getItemShopId(tester)).thenReturn("legacy-tag");

    assertTrue(matcher.matches(original, tester));
  }

  @Test
  void shopIdAliasDoesNotOverrideDifferentTags() {

    final ItemStack original = stack(Material.STONE, false);
    final ItemStack tester = stack(Material.STONE, false);
    lenient().when(platform.getItemShopId(original)).thenReturn("tag-a");
    lenient().when(platform.getItemShopId(tester)).thenReturn("tag-b");

    assertFalse(matcher.matches(original, tester));
  }

  @Test
  void shopIdAliasDoesNotOverrideCrossMaterial() {

    final ItemStack original = stack(Material.STONE, false);
    final ItemStack tester = stack(Material.DIRT, false);
    lenient().when(platform.getItemShopId(original)).thenReturn("legacy-tag");

    assertFalse(matcher.matches(original, tester));
  }

  @Test
  void nullHandlingMatchesContract() {

    assertTrue(matcher.matches(null, null));
    assertFalse(matcher.matches(null, mock(ItemStack.class)));
    assertFalse(matcher.matches(mock(ItemStack.class), null));
  }
}
