package com.ghostchu.quickshop.command.subcommand;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.database.DatabaseIOUtil;
import com.ghostchu.quickshop.database.SimpleDatabaseHelperV2;
import com.ghostchu.quickshop.database.TableZipCsvBackup;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import com.ghostchu.quickshop.shop.tag.QuickShopTagManager;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logger.Log;
import org.bukkit.command.ConsoleCommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class SubCommand_Recovery implements CommandHandler<ConsoleCommandSender> {

  private static final AtomicBoolean RECOVERY_RUNNING = new AtomicBoolean(false);

  private final QuickShop plugin;

  public SubCommand_Recovery(final QuickShop plugin) {

    this.plugin = plugin;
  }

  @Override
  public void onCommand(@NotNull final ConsoleCommandSender sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    final File file = new File(plugin.getDataFolder(), "recovery.zip");
    if(!file.exists()) {
      plugin.text().of(sender, "importing-not-found", "recovery.zip").send();
      return;
    }

    if(parser.getArgs().isEmpty() || !"confirm".equalsIgnoreCase(parser.getArgs().getFirst())) {
      plugin.text().of(sender, "importing-early-warning").send();
      return;
    }

    if(!RECOVERY_RUNNING.compareAndSet(false, true)) {
      plugin.text().of(sender, "operation-in-progress").send();
      return;
    }

    plugin.text().of(sender, "importing-database").send();
    Log.debug("Initializing database recovery...");
    final DatabaseIOUtil databaseIOUtil = new DatabaseIOUtil((SimpleDatabaseHelperV2)plugin.getDatabaseHelper());
    Util.asyncThreadRun(()->this.runRecovery(sender, file, databaseIOUtil));
  }

  private void runRecovery(@NotNull final ConsoleCommandSender sender, @NotNull final File file, @NotNull final DatabaseIOUtil databaseIOUtil) {

    boolean validated = false;
    try {
      // the backup must exist BEFORE anything is touched; a failed backup aborts the whole
      // recovery while database and memory are still intact
      if(!databaseIOUtil.performBackup("recovery")) {
        plugin.text().of(sender, "importing-backup-failed").send();
        plugin.logger().warn("Recovery aborted: the automatic pre-import backup failed. Nothing was modified.");
        return;
      }
      // validate the archive completely (entries, schemas, CsvJdbc readability) before the
      // first destructive step; a rejected file also leaves everything intact
      TableZipCsvBackup.validateImport(file);
      validated = true;
    } catch(final Throwable t) {
      plugin.text().of(sender, "importing-failed", t.getMessage()).send();
      plugin.logger().warn("Recovery aborted during pre-flight checks. Nothing was modified.", t);
    } finally {
      // a rejected run releases the guard here; once validation passed, the import phase
      // owns the flag and releases it when importAndRestore finishes
      if(!validated) {
        RECOVERY_RUNNING.set(false);
      }
    }
    if(!validated) {
      return;
    }

    Log.debug("Unloading all shops...");
    Util.mainThreadRun(()->{
      try {
        plugin.getShopManager().getAllShops().forEach(s->plugin.getShopManager().unloadShop(s));
        // the imported tables replace everything the dirty-save flush would write, so
        // skip the up-to-30s main-thread flush here
        ((SimpleShopManager)plugin.getShopManager()).clear(false);
      } finally {
        Util.asyncThreadRun(()->this.importAndRestore(sender, file));
      }
    });
  }

  private void importAndRestore(@NotNull final ConsoleCommandSender sender, @NotNull final File file) {

    try {
      TableZipCsvBackup.importTables(file, ((SimpleDatabaseHelperV2)plugin.getDatabaseHelper()).getManager());
      // the import rewrote every table; drop the write-path caches so stale data row
      // ids can never leak into subsequent saves
      ((SimpleDatabaseHelperV2)plugin.getDatabaseHelper()).invalidateCaches();
      // the tag tables were purged and rewritten too; the in-memory tag state must not
      // keep (or merge into) the pre-import entries
      ((QuickShopTagManager)plugin.tagManager()).reloadFromDB();
      this.checkImportedSchemaVersion(sender);
      Log.debug("Re-loading shop from database...");
      // stay async: loadShops does a full-table JDBC fetch and joins every shop's
      // deserialization future — on the main thread that freezes the whole server
      plugin.getShopLoader().loadShops();
      plugin.text().of(sender, "imported-database", "recovery.zip").send();
    } catch(final Throwable t) {
      // the import is transactional: the database rolled back to its pre-import state, so
      // reloading from it restores the shop system instead of leaving it dead; drop the
      // write-path caches first, since a partially-failed import may have replaced tables
      // the cached data row ids still point into
      ((SimpleDatabaseHelperV2)plugin.getDatabaseHelper()).invalidateCaches();
      ((QuickShopTagManager)plugin.tagManager()).reloadFromDB();
      Log.debug("Re-loading shops from the surviving database after failed import...");
      plugin.getShopLoader().loadShops();
      plugin.text().of(sender, "importing-failed", t.getMessage()).send();
      plugin.logger().warn("Failed to import the database from backup file. The database was rolled back to its pre-import state.", t);
    } finally {
      RECOVERY_RUNNING.set(false);
    }
  }

  private void checkImportedSchemaVersion(@NotNull final ConsoleCommandSender sender) {

    try {
      final int importedVersion = ((SimpleDatabaseHelperV2)plugin.getDatabaseHelper()).getDatabaseVersion();
      if(importedVersion > SimpleDatabaseHelperV2.LATEST_DATABASE_VERSION) {
        plugin.text().of(sender, "importing-schema-newer", importedVersion, SimpleDatabaseHelperV2.LATEST_DATABASE_VERSION).send();
        plugin.logger().warn("The imported database uses schema version {} which is NEWER than this build supports ({}). Restarting the server will be refused; restore the backup taken before the import.", importedVersion, SimpleDatabaseHelperV2.LATEST_DATABASE_VERSION);
      } else if(importedVersion < SimpleDatabaseHelperV2.LATEST_DATABASE_VERSION) {
        plugin.text().of(sender, "importing-schema-older", importedVersion, SimpleDatabaseHelperV2.LATEST_DATABASE_VERSION).send();
        plugin.logger().info("The imported database uses schema version {}; migrations to version {} will run on the next restart.", importedVersion, SimpleDatabaseHelperV2.LATEST_DATABASE_VERSION);
      }
    } catch(final Throwable t) {
      plugin.logger().warn("Unable to read the schema version of the imported database.", t);
    }
  }

}
