package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.obj.QUserImpl;
import com.ghostchu.quickshop.shop.datatype.HopperPersistentData;
import com.ghostchu.quickshop.shop.datatype.HopperPersistentDataType;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logging.container.ShopRemoveLog;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dropper;
import org.bukkit.block.Hopper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ShopProtectionListener extends AbstractProtectionListener {

  private final NamespacedKey hopperKey = new NamespacedKey(QuickShop.getInstance().getJavaPlugin(), "hopper-persistent-data");
  private final NamespacedKey dropperKey = new NamespacedKey(QuickShop.getInstance().getJavaPlugin(), "dropper-persistent-data");
  private boolean hopperProtect;
  private boolean hopperOwnerExclude;
  private boolean dropperProtect;
  private boolean dropperOwnerExclude;
  private boolean entityProtect;
  private boolean explodeProtect;

  public ShopProtectionListener(@NotNull final QuickShop plugin) {

    super(plugin);
    init();
  }

  private void init() {

    this.hopperProtect = plugin.getConfig().getBoolean("protect.hopper", true);
    this.hopperOwnerExclude = plugin.getConfig().getBoolean("protect.hopper-owner-exclude", false);
    this.dropperProtect = plugin.getConfig().getBoolean("protect.dropper", true);
    this.dropperOwnerExclude = plugin.getConfig().getBoolean("protect.dropper-owner-exclude", false);
    this.entityProtect = plugin.getConfig().getBoolean("protect.entity", true);
    this.explodeProtect = plugin.getConfig().getBoolean("protect.explode");
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockExplode(final BlockExplodeEvent e) {

    for(int i = 0, a = e.blockList().size(); i < a; i++) {
      final Block b = e.blockList().get(i);
      Shop shop = getShopNature(b.getLocation(), true);
      if(shop == null) {
        shop = getShopNextTo(b.getLocation());
      }
      if(shop != null) {
        if(this.explodeProtect) {
          e.setCancelled(true);
        } else {
          plugin.logEvent(new ShopRemoveLog(QUserImpl.createFullFilled(CommonUtil.getNilUniqueId(), "Exploding", false), "BlockBreak(explode)", shop.saveToInfoStorage()));
          plugin.getShopManager().deleteShop(shop);
        }
      }
    }
  }

  /**
   * Gets the shop a sign is attached to
   *
   * @param loc The location of the sign
   *
   * @return The shop
   */
  @Nullable
  private Shop getShopNextTo(@NotNull final Location loc) {

    final Block b = Util.getAttached(loc.getBlock());
    // Util.getAttached(b)
    if(b == null) {
      return null;
    }

    return getShopNature(b.getLocation(), false);
  }

  /*
   * Handles shops breaking through entity changes (like Wither etc.)
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityBlockChange(final EntityChangeBlockEvent e) {

    if(!this.entityProtect) {
      return;
    }
    if(getShopNature(e.getBlock().getLocation(), true) != null) {
      e.setCancelled(true);
    }
  }

  /*
   * Handles shops breaking through explosions
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onExplode(final EntityExplodeEvent e) {

    for(int i = 0, a = e.blockList().size(); i < a; i++) {
      final Block b = e.blockList().get(i);
      Shop shop = getShopNature(b.getLocation(), true);
      if(shop == null) {
        shop = getShopNextTo(b.getLocation());
      }
      if(shop == null) {
        continue;
      }

      if(this.explodeProtect) {
        e.setCancelled(true);
      } else {
        plugin.logEvent(new ShopRemoveLog(QUserImpl.createFullFilled(CommonUtil.getNilUniqueId(), "EntityExploding", false), "BlockBreak(explode)", shop.saveToInfoStorage()));
        plugin.getShopManager().deleteShop(shop);
      }
    }
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void onHopperMoveItem(final InventoryMoveItemEvent event) {

    // Paper: getHolder() resolves the holder through a full block-state snapshot (the
    // backing block entity's item list gets copied) just to answer this instanceof;
    // getHolder(false) answers the same question against the live state. The PDC read
    // below still goes through the snapshot holder — the rare branch pays the old cost,
    // the every-move gate no longer does
    if(!this.hopperProtect || !(event.getDestination().getHolder(false) instanceof Hopper)) {
      return;
    }

    final Location loc = event.getSource().getLocation();
    if(loc == null) {
      return;
    }

    final Shop shop = getShopRedstone(loc, true);

    if(shop == null) {
      return;
    }

    if(this.hopperOwnerExclude && event.getDestination().getHolder() instanceof final Hopper hopper) {
      final HopperPersistentData hopperPersistentData = hopper.getPersistentDataContainer().get(hopperKey, HopperPersistentDataType.INSTANCE);
      if(hopperPersistentData != null) {
        if(shop.playerAuthorize(hopperPersistentData.getPlayer(), BuiltInShopPermission.ACCESS_INVENTORY)) {
          return;
        }
      }
    }
    event.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void onDropperMoveItem(final InventoryMoveItemEvent event) {

    // same live-holder gate as the hopper handler; snapshot holder only in the branch
    if(!this.dropperProtect || !(event.getInitiator().getHolder(false) instanceof Dropper)) {
      return;
    }

    final Location loc = event.getDestination().getLocation();
    if(loc == null) {
      return;
    }

    final Shop shop = getShopRedstone(loc, true);

    if(shop == null) {
      return;
    }

    if(this.dropperOwnerExclude && event.getInitiator().getHolder() instanceof final Dropper dropper) {
      final HopperPersistentData hopperPersistentData = dropper.getPersistentDataContainer().get(dropperKey, HopperPersistentDataType.INSTANCE);
      if(hopperPersistentData != null) {
        if(shop.playerAuthorize(hopperPersistentData.getPlayer(), BuiltInShopPermission.ACCESS_INVENTORY)) {
          return;
        }
      }
    }
    event.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onPlaceProtectedBlock(final BlockPlaceEvent e) {

    // the material read is free; getState() snapshots the placed block entity, so it
    // belongs behind the gate. Only a hopper block yields a Hopper state (and only a
    // dropper a Dropper), so the gate reorders the instanceof without changing it
    final Material placedType = e.getBlockPlaced().getType();
    if(placedType == Material.HOPPER) {
      if(e.getBlockPlaced().getState() instanceof final Hopper hopper) {
        hopper.getPersistentDataContainer().set(hopperKey, HopperPersistentDataType.INSTANCE, new HopperPersistentData(e.getPlayer().getUniqueId()));
        hopper.setBlockData(e.getBlockPlaced().getBlockData());
        hopper.update();
      }
    } else if(placedType == Material.DROPPER) {
      if(e.getBlockPlaced().getState() instanceof final Dropper dropper) {
        dropper.getPersistentDataContainer().set(dropperKey, HopperPersistentDataType.INSTANCE, new HopperPersistentData(e.getPlayer().getUniqueId()));
        dropper.setBlockData(e.getBlockPlaced().getBlockData());
        dropper.update();
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onStructureGrow(final StructureGrowEvent e) {

    for(final BlockState block : e.getBlocks()) {
      if(getShopNature(block.getLocation(), true) != null) {
        e.setCancelled(true);
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onPistonExtend(final BlockPistonExtendEvent event) {

    final List<Block> affectedBlocks = event.getBlocks();
    for(final Block block : affectedBlocks) {

      if(getShopNature(block.getLocation(), true) != null) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onPistonRetract(final BlockPistonRetractEvent event) {

    final List<Block> affectedBlocks = event.getBlocks();

    for(final Block block : affectedBlocks) {
      if(getShopNature(block.getLocation(), true) != null) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @Override
  public ReloadResult reloadModule() {

    init();
    return ReloadResult.builder().status(ReloadStatus.SUCCESS).build();
  }
}
