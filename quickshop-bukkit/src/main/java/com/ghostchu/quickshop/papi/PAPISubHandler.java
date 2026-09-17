package com.ghostchu.quickshop.papi;

import com.ghostchu.quickshop.common.util.CommonUtil;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface PAPISubHandler {

  @Nullable
  default String handle(@NotNull final OfflinePlayer player, @NotNull final String params) {

    final String raw = CommonUtil.subAfter(params, getPrefix() + "_");
    return handle0(player, raw);
  }

  @NotNull
  String getPrefix();

  /**
   * Handlers backed by database queries must return {@code true}: their values are served
   * from a stale-while-revalidate memory cache and refreshed off the main thread, so a
   * cache miss can never park a JDBC round-trip inside chat/tab placeholder resolution.
   */
  default boolean useSoftCache() {

    return false;
  }

  @Nullable
  String handle0(@NotNull OfflinePlayer player, @NotNull String paramsTrimmed);
}
