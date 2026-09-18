package com.ghostchu.quickshop.addon.displaycontrol.database;

import cc.carm.lib.easysql.api.SQLManager;
import cc.carm.lib.easysql.api.SQLQuery;
import com.ghostchu.quickshop.addon.displaycontrol.Main;
import com.ghostchu.quickshop.addon.displaycontrol.bean.DisplayOption;
import com.ghostchu.quickshop.util.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

public class DisplayControlDatabaseHelper {

  private final Main plugin;

  public DisplayControlDatabaseHelper(@NotNull final Main plugin, @NotNull final SQLManager sqlManager, @NotNull final String dbPrefix) throws SQLException {

    this.plugin = plugin;
    try {
      DisplayControlTables.initializeTables(sqlManager, dbPrefix);
    } catch(SQLException e) {
      plugin.getLogger().log(Level.WARNING, "Cannot initialize tables", e);
      throw e;
    }
    migrateLegacyDuplicates(sqlManager);

  }

  /**
   * Installs before the unique-index schema raced the select-then-insert toggle into several
   * rows per player; reads picked an arbitrary one. Collapse to the oldest row per player and
   * enforce uniqueness so the REPLACE-based toggle stays race-free. New installs already get
   * the unique index from the table DDL. Best-effort: failures only degrade to the old
   * behavior, and are reported.
   */
  private void migrateLegacyDuplicates(@NotNull final SQLManager sqlManager) {

    // executeSQL swallows SQLException into the manager's default handler and returns null on failure
    final String table = DisplayControlTables.DISPLAY_CONTROL_PLAYERS.getName();
    final Integer deleted = sqlManager.executeSQL("DELETE FROM `" + table + "` WHERE id NOT IN (SELECT MIN(id) FROM `" + table + "` GROUP BY player)");
    final Integer created = sqlManager.executeSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_qs_addon_display_control_psettings ON `" + table + "` (`player`)");
    if(deleted == null || created == null) {
      plugin.getLogger().warning("Failed to dedupe/enforce unique player index on " + table + "; duplicate display-option rows may persist. (deleted=" + deleted + ", indexed=" + created + ")");
    }
  }

  public @NotNull Integer setDisplayDisableForPlayer(@NotNull final UUID uuid, final DisplayOption status) throws SQLException {

    Util.ensureThread(true);
    return DisplayControlTables.DISPLAY_CONTROL_PLAYERS.createReplace()
            .setColumnNames("player", "displayOption")
            .setParams(uuid.toString(), status.getId())
            .execute();
  }

  @Nullable
  public DisplayOption getDisplayOption(@NotNull final UUID player) throws SQLException {

    Util.ensureThread(true);
    try(SQLQuery query = DisplayControlTables.DISPLAY_CONTROL_PLAYERS
            .createQuery()
            .selectColumns("displayOption")
            .addCondition("player", player.toString()).setLimit(1).build().execute();
        ResultSet set = query.getResultSet()) {
      if(set.next()) {
        final int optionId = set.getInt("displayOption");
        return DisplayOption.fromId(optionId);
      }
      return DisplayOption.AUTO;
    }
  }
}
