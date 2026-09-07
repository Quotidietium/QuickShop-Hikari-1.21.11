package com.ghostchu.quickshop.api.shop.permission;

import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.shop.ShopPermissionAudience;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public enum BuiltInShopPermission implements ShopPermissionAudience {
  PURCHASE("purchase", "purchase"),
  SHOW_INFORMATION("show_information", "show-information"),
  PREVIEW_SHOP("preview_shop", "preview-shop"),
  SEARCH("search", "search"),
  DELETE("delete", "delete"),
  RECEIVE_ALERT("alert.receive", "receive-alert"),
  ACCESS_INVENTORY("access_inventory", "access-inventory"),
  OWNERSHIP_TRANSFER("ownership_transfer", "ownership-transfer"),
  MANAGEMENT_PERMISSION("management_permission", "management-permission"),
  TOGGLE_DISPLAY("toggle_display", "toggle-display"),
  SET_SHOPTYPE("set_shoptype", "set-shoptype"),
  SET_SHOP_STATE("set_shop_state", "set-shop-state"),
  SET_PRICE("set_price", "set-price"),
  SET_ITEM("set_item", "set-item"),
  SET_STACK_AMOUNT("set_stack_amount", "set-stack-amount"),
  SET_NAME("set_name", "set-name"),
  SET_BENEFIT("set_benefit", "set-benefit"),
  SET_SIGN_TYPE("set_sign_type", "set-sign-type"),
  VIEW_PURCHASE_LOGS("view_purchase_logs", "view-purchase-logs");

  private final String node;

  private final String descriptionKey;

  /** Lazy memo of {@link #getNamespacedNode()}; the plugin name is constant per run. */
  private String namespacedNode;

  BuiltInShopPermission(@NotNull final String node, @NotNull final String descriptionKey) {

    this.node = node;
    this.descriptionKey = descriptionKey;
  }

  @NotNull
  public String getDescriptionKey() {

    return descriptionKey;
  }

  @Override
  public @NotNull String getName() {

    return this.descriptionKey;
  }

  @Override
  public boolean hasPermission(@NotNull final BuiltInShopPermission permission) {

    return this == permission;
  }

  @Override
  public boolean hasPermission(@NotNull final String permission) {

    return this.node.equals(permission);
  }

  @NotNull
  public String getNamespacedNode() {

    // every permission check resolves the namespace through the services manager and
    // rebuilds the string; the plugin name cannot change at runtime, so memoize (the
    // benign race only ever rebuilds an equal string)
    final String cached = this.namespacedNode;
    if(cached != null) {
      return cached;
    }
    final String built = QuickShopAPI.getPluginInstance().getName().toLowerCase(Locale.ROOT) + "." + this.node;
    this.namespacedNode = built;
    return built;
  }

  @NotNull
  public String getRawNode() {

    return node;
  }
}
