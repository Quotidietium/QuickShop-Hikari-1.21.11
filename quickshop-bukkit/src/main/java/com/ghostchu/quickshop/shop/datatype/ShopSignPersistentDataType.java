package com.ghostchu.quickshop.shop.datatype;

import com.ghostchu.quickshop.common.util.JsonUtil;
import com.ghostchu.quickshop.shop.ShopSignStorage;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ShopSignPersistentDataType
        implements PersistentDataType<String, ShopSignStorage> {

  public static final ShopSignPersistentDataType INSTANCE = new ShopSignPersistentDataType();

  @Override
  public @NotNull Class<String> getPrimitiveType() {

    return String.class;
  }

  @Override
  public @NotNull Class<ShopSignStorage> getComplexType() {

    return ShopSignStorage.class;
  }

  @NotNull
  @Override
  public String toPrimitive(
          @NotNull final ShopSignStorage complex, @NotNull final PersistentDataAdapterContext context) {

    return JsonUtil.getGson().toJson(complex);
  }

  @Override
  public @Nullable ShopSignStorage fromPrimitive(
          @NotNull final String primitive, @NotNull final PersistentDataAdapterContext context) {

    // third-party writes/crashes can leave corrupt JSON in the sign's PDC; letting the
    // exception escape turned every later shop deletion into a half-completed state
    // (marked deleted but never unregistered/refunded, because getSigns() blew up mid
    // cleanup). A null read degrades to "not a claimed shop sign"
    try {
      return JsonUtil.getGson().fromJson(primitive, ShopSignStorage.class);
    } catch(final RuntimeException e) {
      return null;
    }
  }

}
