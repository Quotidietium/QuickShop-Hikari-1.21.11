package com.ghostchu.quickshop.database;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.logger.Log;
import dev.dejvokep.boostedyaml.block.implementation.Section;

import java.util.Map;
import java.util.Set;

public class HikariUtil {

  /**
   * Pool-level keys that must reach the HikariCP setters. Everything else in
   * database.properties is a JDBC driver property and goes to
   * {@code addDataSourceProperty}. Routing everything through the latter (as this
   * method used to) silently ignored the pool tuning: maximumPoolSize stayed at the
   * HikariCP default no matter what the config said, and pool keys leaked to the
   * driver as unknown connection properties.
   */
  private static final Set<String> POOL_INT_KEYS = Set.of("maximumPoolSize", "minimumIdle");
  private static final Set<String> POOL_LONG_KEYS = Set.of("connectionTimeout", "idleTimeout",
                                                           "maxLifetime", "keepaliveTime",
                                                           "leakDetectionThreshold");

  private HikariUtil() {

  }

  public static cc.carm.lib.easysql.hikari.HikariConfig createHikariConfig() {

    final cc.carm.lib.easysql.hikari.HikariConfig config = new cc.carm.lib.easysql.hikari.HikariConfig();
    Section section = QuickShop.getInstance().getConfig().getSection("database");
    if(section == null) {
      throw new IllegalArgumentException("database section in configuration not found");
    }
    section = section.getSection("properties");
    if(section == null) {
      throw new IllegalArgumentException("database.properties section in configuration not found");
    }
    for(final Object keyObj : section.getKeys()) {

      final String key = String.valueOf(keyObj);
      final String value = String.valueOf(section.get(key));
      if(POOL_INT_KEYS.contains(key)) {
        try {
          final int parsed = Integer.parseInt(value.trim());
          if(key.equals("maximumPoolSize")) {
            config.setMaximumPoolSize(parsed);
          } else {
            config.setMinimumIdle(parsed);
          }
        } catch(final NumberFormatException e) {
          QuickShop.getInstance().logger().warn("Ignoring invalid pool property {} = {} (not a number)", key, value);
        }
      } else if(POOL_LONG_KEYS.contains(key)) {
        try {
          final long parsed = Long.parseLong(value.trim());
          switch(key) {
            case "connectionTimeout" -> config.setConnectionTimeout(parsed);
            case "idleTimeout" -> config.setIdleTimeout(parsed);
            case "maxLifetime" -> config.setMaxLifetime(parsed);
            case "keepaliveTime" -> config.setKeepaliveTime(parsed);
            case "leakDetectionThreshold" -> config.setLeakDetectionThreshold(parsed);
            default -> { /* unreachable: POOL_LONG_KEYS is exhaustive over the cases */ }
          }
        } catch(final NumberFormatException e) {
          QuickShop.getInstance().logger().warn("Ignoring invalid pool property {} = {} (not a number)", key, value);
        }
      } else {
        config.addDataSourceProperty(key, value);
      }
    }
    Log.debug("HikariCP Config created with properties: " + config.getDataSourceProperties());
    return config;
  }
}
