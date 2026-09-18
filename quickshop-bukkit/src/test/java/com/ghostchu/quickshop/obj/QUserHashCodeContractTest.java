package com.ghostchu.quickshop.obj;

import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.common.util.CommonUtil;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the equals/hashCode contract: equals() compares only the identity field per
 * player type (uniqueId for real players, username for virtuals) — hashCode must mirror
 * that, otherwise a real-player QUser whose async-filled username changes afterwards
 * vanishes from hash-based collections mid-flight.
 */
class QUserHashCodeContractTest {

  @Test
  void realPlayerHashIgnoresUsernameMutation() {

    final UUID uuid = UUID.randomUUID();
    final QUser player = QUserImpl.createFullFilled(uuid, "Alice", true);

    final int before = player.hashCode();
    player.setUsername("Bob");
    assertEquals(before, player.hashCode(), "hash must stay stable across the async username fill");
    assertEquals(player, QUserImpl.createFullFilled(uuid, "Whatever", true));
  }

  @Test
  void realPlayerAndVirtualAreDistinctEvenWithSameUsername() {

    final QUser real = QUserImpl.createFullFilled(UUID.randomUUID(), "Alice", true);
    // virtuals carry the nil UUID in production (name-based lookups)
    final QUser virtual = QUserImpl.createFullFilled(CommonUtil.getNilUniqueId(), "Alice", false);

    assertNotEquals(real, virtual, "real vs virtual with the same name are different identities");
    assertNotEquals(real.hashCode(), virtual.hashCode());
  }

  @Test
  void virtualHashKeysOnUsername() {

    final QUser a = QUserImpl.createFullFilled(CommonUtil.getNilUniqueId(), "Console", false);
    final QUser b = QUserImpl.createFullFilled(CommonUtil.getNilUniqueId(), "Console", false);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void survivesHashBasedCollectionAcrossUsernameMutation() {

    final UUID uuid = UUID.randomUUID();
    final QUser player = QUserImpl.createFullFilled(uuid, "Alice", true);

    final Map<QUser, String> map = new HashMap<>();
    map.put(player, "shop-owner");

    player.setUsername("Bob");
    assertEquals("shop-owner", map.get(QUserImpl.createFullFilled(uuid, "x", true)),
                 "lookup by identity must still find the entry after the username changed");
  }
}
