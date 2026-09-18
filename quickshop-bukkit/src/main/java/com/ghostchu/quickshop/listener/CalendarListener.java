package com.ghostchu.quickshop.listener;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.event.CalendarEvent;
import com.ghostchu.quickshop.database.SimpleDatabaseHelperV2;
import com.ghostchu.quickshop.util.MsgUtil;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import org.bukkit.event.EventHandler;

import java.util.Calendar;

/**
 * Weekly database housekeeping on the CalendarWatcher's WEEK tick.
 *
 * <p>Before this listener existed the event had no consumers at all: expired offline
 * messages were only cleaned at server startup (months-long uptimes kept accumulating
 * them), and the four log tables had no cleanup path besides the manual
 * {@code /qs database purgelogs} command.</p>
 */
public class CalendarListener extends AbstractQSListener {

  private int autoPurgeDays;

  public CalendarListener(final QuickShop plugin) {

    super(plugin);
    init();
  }

  private void init() {

    // 0 (or absent) = keep the current behavior: logs are never auto-deleted
    this.autoPurgeDays = plugin.getConfig().getInt("logging.auto-purge-days", 0);
  }

  @EventHandler
  public void onCalendar(final CalendarEvent event) {

    if(event.getCalendarTriggerType() != CalendarEvent.CalendarTriggerType.WEEK) {
      return;
    }
    // offline messages expire after one week by design; sweep them weekly so long
    // uptimes match the startup-only clean
    Util.asyncThreadRun(MsgUtil::clean);
    if(autoPurgeDays <= 0) {
      return;
    }
    final int days = autoPurgeDays;
    Util.asyncThreadRun(()->{
      if(!(plugin.getDatabaseHelper() instanceof final SimpleDatabaseHelperV2 helper)) {
        return;
      }
      final Calendar calendar = Calendar.getInstance();
      calendar.add(Calendar.DATE, -days);
      plugin.logger().info("Auto-purging database logs older than {} days...", days);
      helper.purgeLogsRecords(calendar.getTime()).whenComplete((lines, err)->{
        if(err != null) {
          plugin.logger().warn("Automatic log purge failed.", err);
        } else if(lines != null && lines >= 0) {
          plugin.logger().info("Automatic log purge removed {} lines.", lines);
        }
      });
    });
  }

  @Override
  public ReloadResult reloadModule() {

    init();
    return ReloadResult.builder().status(ReloadStatus.SUCCESS).build();
  }
}
