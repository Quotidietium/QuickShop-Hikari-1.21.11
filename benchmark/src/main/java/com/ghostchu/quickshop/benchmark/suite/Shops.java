package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.benchmark.Env;
import com.ghostchu.quickshop.obj.QUserImpl;
import com.ghostchu.quickshop.shop.ContainerShop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared factory producing real {@link ContainerShop} instances over mock ItemStacks, so
 * benchmark hot loops measure production field reads instead of Mockito interception.
 */
public final class Shops {

  private Shops() {

  }

  public static ContainerShop create(final World world, final int x, final int y, final int z, final int index) {

    final QuickShop plugin = Env.plugin();
    final Location location = new Location(world, x, y, z);
    final ItemStack item = mock(ItemStack.class);
    when(item.getType()).thenReturn(Material.DIAMOND);
    when(item.getAmount()).thenReturn(64);
    when(item.hasItemMeta()).thenReturn(false);
    when(item.clone()).thenReturn(item);
    final int ownerIndex = index % 100;
    final com.ghostchu.quickshop.api.obj.QUser owner = QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes(("owner-" + ownerIndex).getBytes(StandardCharsets.UTF_8)),
            "owner-" + ownerIndex, true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    when(benefit.serialize()).thenReturn("{}");
    final Map<UUID, String> playerGroup = new HashMap<>();
    playerGroup.put(owner.getUniqueId(), "quickshop.builtin.administrator");
    return new ContainerShop(
            plugin, -1L, location, 10.0d + (index % 977), item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new YamlConfiguration(), null, false, null,
            "Bukkit", "sym-" + index, index % 8 == 0? ("shop-" + index) : null,
            playerGroup, benefit);
  }
}
