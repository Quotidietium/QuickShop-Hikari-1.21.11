package com.ghostchu.quickshop.addon.discount;

import com.ghostchu.quickshop.addon.discount.type.CodeType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the name-keyed store: codes used to live in a HashSet keyed by DiscountCode
 * identity, whose hashCode covered mutable fields — editing a code stranded it in the
 * wrong bucket and remove became a no-op (the code resurrected on every save).
 *
 * <p>Uses the (main, file) seam with a null plugin handle: the manager never dereferences
 * the plugin unless a save fails.</p>
 */
class DiscountCodeManagerStoreTest {

  @TempDir
  File dataFolder;

  private DiscountCodeManager newManager() throws Exception {

    return new DiscountCodeManager(null, new File(dataFolder, "data.yml"));
  }

  private void seedCode(final String name) throws Exception {

    final DiscountCode code = new DiscountCode(UUID.randomUUID(), name, CodeType.SERVER_ALL_SHOPS,
                                                new DiscountCode.PercentageDiscountRate(0.5d), -1, -1d, -1);
    final YamlConfiguration seed = new YamlConfiguration();
    seed.set("codes", List.of(code.saveToString()));
    seed.save(new File(dataFolder, "data.yml"));
  }

  @Test
  void lookupIsCaseInsensitive() throws Exception {

    seedCode("Save10");
    final DiscountCodeManager manager = newManager();

    assertNotNull(manager.getCode("save10"), "lookup must be case-insensitive");
    assertNotNull(manager.getCode("SAVE10"));
    assertEquals("Save10", manager.getCode("sAvE10").getCode());
  }

  @Test
  void editedCodeRemainsRemovable() throws Exception {

    seedCode("EDIT_ME");
    final DiscountCodeManager manager = newManager();
    final DiscountCode code = manager.getCode("EDIT_ME");
    assertNotNull(code);

    // mutating the fields that used to feed hashCode must not break later removal
    code.setMaxUsage(5);
    code.setThreshold(99d);
    manager.removeCode(code);
    manager.saveDatabase();

    assertNull(manager.getCode("edit_me"), "remove must work after config edits");
  }

  @Test
  void removalPersistsAcrossManagerReload() throws Exception {

    seedCode("GONE");
    final DiscountCodeManager manager = newManager();
    assertNotNull(manager.getCode("gone"));
    manager.removeCode(manager.getCode("gone"));
    manager.saveDatabase();

    final DiscountCodeManager reloaded = newManager();
    assertNull(reloaded.getCode("gone"), "a removed code must not resurrect on reload");
  }

  @Test
  void getCodesSnapshotIsImmutableAndDecoupled() throws Exception {

    seedCode("SNAP");
    final DiscountCodeManager manager = newManager();
    final var snapshot = manager.getCodes();
    assertEquals(1, snapshot.size());

    manager.removeCode(manager.getCode("snap"));
    final DiscountCode staleRef = snapshot.iterator().next();
    assertEquals(1, snapshot.size(), "snapshot must be decoupled from the live store");
    assertThrows(UnsupportedOperationException.class, ()->snapshot.add(staleRef));
    assertFalse(manager.getCodes().contains(staleRef));
  }
}
