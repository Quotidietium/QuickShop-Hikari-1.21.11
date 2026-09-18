package com.ghostchu.quickshop.database;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.logger.Log;
import lombok.Data;

import java.io.File;

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

/**
 * DatabaseIOUtil produces the plugin's automatic backups. The export itself lives in
 * {@link TableZipCsvBackup} (single implementation, schema sidecars included, so every
 * backup this class writes is importable by the recovery command).
 */
@Data
public class DatabaseIOUtil {

  private final SimpleDatabaseHelperV2 helper;

  public DatabaseIOUtil(final SimpleDatabaseHelperV2 helper) {

    this.helper = helper;
  }

  /**
   * Creates a backup archive under backup/&lt;reason&gt;/&lt;timestamp&gt;.zip.
   *
   * @return true when the backup was created successfully or disabled by configuration
   * (backup-policy.&lt;reason&gt;: false); false on any failure — callers that are about
   * to destroy data MUST abort when this returns false.
   */
  public boolean performBackup(final String reason) {

    try {
      if(!QuickShop.getInstance().getConfig().getBoolean("backup-policy." + reason, true)) {
        Log.debug("The backup " + reason + " has been disabled in configuration.");
        return true;
      }

      File backupFile = new File(QuickShop.getInstance().getDataFolder(), "backup");
      if(!backupFile.exists()) {
        if(!backupFile.mkdirs()) {
          QuickShop.getInstance().logger().warn("[DB Backup] Failed to create backup directory");
          return false;
        }
      }
      backupFile = new File(backupFile, reason);
      if(!backupFile.exists()) {
        if(!backupFile.mkdirs()) {
          QuickShop.getInstance().logger().warn("[DB Backup] Failed to create backup sub-reason directory");
          return false;
        }
      }
      backupFile = new File(backupFile, System.currentTimeMillis() + ".zip");
      try {
        TableZipCsvBackup.exportTables(backupFile);
        return true;
      } catch(final Exception e) {
        QuickShop.getInstance().logger().warn("[DB Backup] Failed to create backup", e);
        return false;
      }
    } catch(final Throwable throwable) {
      QuickShop.getInstance().logger().warn("[DB Backup] Unexpected error", throwable);
      return false;
    }
  }
}
