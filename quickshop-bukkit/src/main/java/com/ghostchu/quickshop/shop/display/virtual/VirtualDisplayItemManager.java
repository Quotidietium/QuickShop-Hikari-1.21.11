package com.ghostchu.quickshop.shop.display.virtual;

/*
 * QuickShop-Hikari
 * Copyright (C) 2025 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.event.packet.handler.PacketHandlerAddedEvent;
import com.ghostchu.quickshop.api.event.packet.handler.PacketHandlerInitEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopChunk;
import com.ghostchu.quickshop.api.shop.display.PacketFactory;
import com.ghostchu.quickshop.api.shop.display.PacketHandler;
import com.ghostchu.quickshop.shop.SimpleShopChunk;
import com.ghostchu.quickshop.shop.display.virtual.packet.PacketEventsHandler;
import com.ghostchu.quickshop.shop.display.virtual.packet.ProtocolLibHandler;
import com.ghostchu.quickshop.util.logger.Log;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class VirtualDisplayItemManager {

  private static VirtualDisplayItemManager instance;
  public final Map<Long, Integer> shopEntities = new ConcurrentHashMap<>();
  protected final Map<String, PacketHandler<?>> packetHandlers = new LinkedHashMap<>();
  @Getter
  private final Map<ShopChunk, List<VirtualDisplayItem<?>>> chunksMapping = new ConcurrentHashMap<>();
  private final QuickShop plugin;
  private final AtomicInteger entityIdCounter;
  private PacketHandler<?> packetHandler;
  private PacketFactory<?> packetFactory;
  private boolean testPassed = true;

  public VirtualDisplayItemManager(final QuickShop plugin) {

    instance = this;

    //We handle our default packet handlers
    addHandler(new PacketEventsHandler());
    addHandler(new ProtocolLibHandler());

    setHandler();

    if(this.packetHandler != null) {
      this.plugin = plugin;

      final PacketHandlerInitEvent initEvent = new PacketHandlerInitEvent(this.packetHandler);
      if(initEvent.callCancellableEvent()) {

        Log.debug("Canceled the initialization of PacketHandler: " + this.packetHandler.identifier());
        throw new IllegalStateException("No suitable packet handler found for virtual display item management. Please make sure either PacketEvents or ProtocolLib is installed.");
      } else {

        this.packetHandler.initialize();
      }
      this.entityIdCounter = new AtomicInteger(Integer.MAX_VALUE);

      load();
    } else {

      throw new IllegalStateException("No suitable packet handler found for virtual display item management. Please make sure either PacketEvents or ProtocolLib is installed.");
    }
  }

  public static VirtualDisplayItemManager instance() {

    return instance;
  }

  public void setHandler() {

    final String preferred = QuickShop.getInstance().getConfig().getString("shop.display-protocol", "protocollib").toLowerCase(Locale.ROOT);

    //attempt to use the preferred packet handler.
    //only consider handlers whose plugin is ENABLED: a backend that loaded but failed to
    //enable (e.g. a packetevents build too old for this server version) already had its
    //PluginClassLoader closed by the server, touching its classes throws NoClassDefFoundError
    final PacketHandler<?> handler = packetHandlers.get(preferred);
    if(handler != null && Bukkit.getPluginManager().isPluginEnabled(handler.pluginName())) {

      this.packetHandler = handler;
      return;
    }

    for(final PacketHandler<?> packetHandler : packetHandlers.values()) {

      if(Bukkit.getPluginManager().isPluginEnabled(packetHandler.pluginName())) {

        this.packetHandler = packetHandler;
      }
    }
  }

  public void load() {

    Log.debug("Attempting to load packet factory...");

    final Optional<PacketFactory<?>> factoryOptional = packetHandler.factory(plugin.platform().getMinecraftVersion());
    if(factoryOptional.isEmpty()) {

      throw new IllegalStateException("No PacketFactory found for platform version " + plugin.platform().getMinecraftVersion());
    }

    this.packetFactory = factoryOptional.get();
    Log.debug("Attempting to register chunk packet listeners...");

    if(packetFactory != null) {

      packetFactory.registerSendChunk();
      packetFactory.registerUnloadChunk();
    }
  }

  public void put(@NotNull final ShopChunk key, @NotNull final VirtualDisplayItem<?> value) {

    //Thread-safe was ensured by ONLY USE Map method to do something
    final List<VirtualDisplayItem<?>> virtualDisplayItems = new ArrayList<>(Collections.singletonList(value));
    chunksMapping.merge(key, virtualDisplayItems, (mapOldVal, mapNewVal)->{

      mapOldVal.addAll(mapNewVal);
      return mapOldVal;
    });
  }

  public void remove(@NotNull final ShopChunk key, @NotNull final VirtualDisplayItem value) {

    chunksMapping.computeIfPresent(key, (mapOldKey, mapOldVal)->{
      mapOldVal.remove(value);
      return mapOldVal;
    });
  }

  /**
   * Resends every spawned virtual display item of the given chunk to the player (chunk
   * sent / re-sent). Registers the player as a packet sender and sends one
   * destroy-spawn-meta sequence per display; sendFakeItem's leading destroy packet
   * already covers a client that still holds the entity from a previous send, so no
   * extra destroy is issued before it.
   *
   * <p>Packets are sent after the mapping lookup finishes so no packet send happens
   * while the mapping bin lock is held (sends may re-enter Bukkit services).</p>
   *
   * @param player the player the chunk was sent to
   * @param world  the chunk's world name
   * @param x      the chunk x coordinate
   * @param z      the chunk z coordinate
   */
  public void resendChunkDisplays(@NotNull final Player player, @NotNull final String world, final int x, final int z) {

    final List<VirtualDisplayItem<?>> targets = new ArrayList<>();
    chunksMapping.computeIfPresent(new SimpleShopChunk(world, x, z), (chunkLoc, targetList)->{

      for(final VirtualDisplayItem<?> target : targetList) {
        if(!target.isSpawned()) {
          continue;
        }
        if(target.isApplicableForPlayer(player)) {
          target.getPacketSenders().add(player.getUniqueId());
          targets.add(target);
        }
      }
      return targetList;
    });

    for(final VirtualDisplayItem<?> target : targets) {
      target.sendFakeItem(player);
    }
  }

  /**
   * Withdraws the player's virtual display items of the given chunk (chunk unloaded
   * client-side): destroys each spawned display and unregisters the player as a packet
   * sender, sending outside the mapping bin lock as above.
   *
   * @param player the player the chunk was unloaded for
   * @param world  the chunk's world name
   * @param x      the chunk x coordinate
   * @param z      the chunk z coordinate
   */
  public void withdrawChunkDisplays(@NotNull final Player player, @NotNull final String world, final int x, final int z) {

    final List<VirtualDisplayItem<?>> targets = new ArrayList<>();
    chunksMapping.computeIfPresent(new SimpleShopChunk(world, x, z), (chunkLoc, targetList)->{

      for(final VirtualDisplayItem<?> target : targetList) {
        if(target.isSpawned()) {
          targets.add(target);
        }
      }
      return targetList;
    });

    for(final VirtualDisplayItem<?> target : targets) {
      target.sendDestroyPacket(player);
      target.getPacketSenders().remove(player.getUniqueId());
    }
  }

  public void unload() {

    Log.debug("Unregistering the packet listener...");
    if(packetFactory != null) {

      packetFactory.unregisterSendChunk();
      packetFactory.unregisterUnloadChunk();
    }
  }

  public int generateEntityId() {

    return entityIdCounter.getAndDecrement();
  }

  @NotNull
  public VirtualDisplayItem<?> createVirtualDisplayItem(@NotNull final Shop shop) {

    return new VirtualDisplayItem<>(this, packetFactory, shop);
  }

  public boolean isTestPassed() {

    return testPassed;
  }

  public void setTestPassed(final boolean testPassed) {

    this.testPassed = testPassed;
  }

  public void addHandler(final PacketHandler<?> packetHandler) {

    final PacketHandlerAddedEvent addedEvent = new PacketHandlerAddedEvent(packetHandler);
    if(addedEvent.callCancellableEvent()) {

      Log.debug("Canceled the addition of PacketHandler: " + packetHandler.identifier());

    } else {

      packetHandlers.put(packetHandler.identifier().toLowerCase(Locale.ROOT), packetHandler);
    }
  }

  public boolean allowEnchants() {
    return plugin.getConfig().getBoolean("shop.display-allow-enchants", true);
  }

  public boolean useItemName() {
    return plugin.getConfig().getBoolean("shop.display-item-use-name");
  }

  public Map<String, PacketHandler<?>> packetHandlers() {

    return packetHandlers;
  }

  public PacketHandler<?> packetHandler() {

    return packetHandler;
  }
}
