package com.ghostchu.quickshop.metric;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.database.ShopMetricRecord;
import com.ghostchu.quickshop.api.database.ShopOperationEnum;
import com.ghostchu.quickshop.api.event.Phase;
import com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent;
import com.ghostchu.quickshop.api.event.general.ShopOngoingFeeEvent;
import com.ghostchu.quickshop.api.event.management.ShopCreateEvent;
import com.ghostchu.quickshop.api.event.management.ShopDeleteEvent;
import com.ghostchu.quickshop.listener.AbstractQSListener;
import com.ghostchu.quickshop.util.logger.Log;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class MetricListener extends AbstractQSListener implements Listener {

  public MetricListener(final QuickShop plugin) {

    super(plugin);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onCreate(final ShopCreateEvent event) {

    if(!event.isPhase(Phase.POST) || event.shop().isEmpty()) {
      return;
    }

    metric(new ShopMetricRecord(
            System.currentTimeMillis(),
            event.shop().get().getShopId(),
            ShopOperationEnum.CREATE,
            plugin.getConfig().getDouble("shop.cost"),
            0.0d,
            0,
            event.user()));
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onDelete(final ShopDeleteEvent event) {

    if(!event.isPhase(Phase.POST) || event.shop().isEmpty()) {
      return;
    }

    metric(new ShopMetricRecord(
            System.currentTimeMillis(),
            event.shop().get().getShopId(),
            ShopOperationEnum.DELETE,
            plugin.getConfig().getBoolean("shop.refund")? plugin.getConfig().getDouble("shop.cost", 0.0d) : 0.0d,
            0.0d,
            0,
            event.shop().get().getOwner()));
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onDelete(final ShopOngoingFeeEvent event) {

    metric(new ShopMetricRecord(
            System.currentTimeMillis(),
            event.getShop().getShopId(),
            ShopOperationEnum.ONGOING_FEE,
            event.getCost(),
            0.0d,
            0,
            event.getShop().getOwner()));
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onPurchase(final ShopSuccessPurchaseEvent event) {

    metric(new ShopMetricRecord(
            System.currentTimeMillis(),
            event.getShop().getShopId(),
            event.getShop().shopType().operationType(),
            event.getBalanceWithoutTax(),
            event.getTax(),
            event.getAmount(),
            event.getPurchaser()));
  }

  /**
   * Routes the record through the MetricBatcher (one batched insert per flush window
   * instead of one statement per trade); falls back to the direct single insert when
   * the batcher is absent (unit-test environments).
   */
  private void metric(final ShopMetricRecord record) {

    final MetricBatcher batcher = plugin.getMetricBatcher();
    if(batcher != null) {
      batcher.offer(record);
      return;
    }
    plugin.getDatabaseHelper().insertMetricRecord(record)
            .exceptionally(e->{
              Log.debug("Failed to insert shop metric record: " + e.getMessage());
              return 0;
            });
  }
}
