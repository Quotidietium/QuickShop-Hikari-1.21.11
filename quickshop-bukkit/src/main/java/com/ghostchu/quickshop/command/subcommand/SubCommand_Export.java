package com.ghostchu.quickshop.command.subcommand;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.api.command.CommandParser;
import com.ghostchu.quickshop.database.TableZipCsvBackup;
import com.ghostchu.quickshop.util.Util;
import org.bukkit.command.ConsoleCommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class SubCommand_Export implements CommandHandler<ConsoleCommandSender> {

  private static final AtomicBoolean EXPORT_RUNNING = new AtomicBoolean(false);

  private final QuickShop plugin;

  public SubCommand_Export(final QuickShop plugin) {

    this.plugin = plugin;
  }

  @Override
  public void onCommand(@NotNull final ConsoleCommandSender sender, @NotNull final String commandLabel, @NotNull final CommandParser parser) {

    if(!EXPORT_RUNNING.compareAndSet(false, true)) {
      plugin.text().of(sender, "operation-in-progress").send();
      return;
    }

    plugin.text().of(sender, "exporting-database").send();
    final File file = new File(QuickShop.getInstance().getDataFolder(), "export-" + System.currentTimeMillis() + ".zip");

    Util.asyncThreadRun(()->{
      try {
        TableZipCsvBackup.exportTables(file);

        plugin.text().of(sender, "exported-database", file.toString()).send();
      } catch(final Throwable t) {
        // CsvJdbc used to die here with a NoClassDefFoundError (an Error) that the old
        // catch clause let vanish silently
        plugin.logger().warn("Exporting database failed.", t);
        plugin.text().of(sender, "exporting-failed", t.getMessage()).send();
      } finally {
        EXPORT_RUNNING.set(false);
      }
    });

  }
}
