package com.ghostchu.quickshop.shop.datatype;

import com.ghostchu.quickshop.shop.ShopSignStorage;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for the R60 PDC corruption tolerance: a third-party write or crash
 * can leave malformed JSON under the shop-sign key; fromPrimitive must degrade to null
 * ("not a claimed sign") instead of throwing JsonSyntaxException out of getSigns() and
 * turning shop deletion into a half-completed state (marked deleted, never unregistered
 * or refunded).
 */
class ShopSignPersistentDataTypeTest {

  private final ShopSignPersistentDataType type = ShopSignPersistentDataType.INSTANCE;
  private final PersistentDataAdapterContext context = mock(PersistentDataAdapterContext.class);

  @Test
  void corruptJsonDegradesToNull() {

    assertNull(type.fromPrimitive("{ this is not json", context));
  }

  @Test
  void truncatedJsonDegradesToNull() {

    assertNull(type.fromPrimitive("{\"world\":\"world_nether\", \"x\":12", context));
  }

  @Test
  void emptyInputDegradesToNull() {

    assertNull(type.fromPrimitive("", context));
  }

  @Test
  void roundTripPreservesStorage() {

    final ShopSignStorage storage = new ShopSignStorage("world_the_end", 100, 64, -220);
    final String encoded = type.toPrimitive(storage, context);
    final ShopSignStorage decoded = type.fromPrimitive(encoded, context);

    assertEquals(storage, decoded);
    assertEquals("world_the_end", decoded.getWorld());
    assertEquals(100, decoded.getX());
    assertEquals(64, decoded.getY());
    assertEquals(-220, decoded.getZ());
  }
}
