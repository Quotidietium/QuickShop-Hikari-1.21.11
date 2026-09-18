package com.ghostchu.quickshop.util.pagination;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * Pins the empty-entry contract of {@link Pagination}. A player without tagged shops
 * used to reach {@code /qs tag shops} with an empty entry list: totalPages computed 0,
 * the page clamp settled page on 0, and the entry loop started at a negative index
 * (IndexOutOfBoundsException out of the command handler).
 */
class PaginationEmptyEntriesTest {

  private PaginationOptions<String> options(final List<String> entries, final int page) {

    return PaginationOptions.builder()
            .setCommand("cmd tag shops")
            .setCurrentPage(page)
            .setEntries(entries)
            .setMaxPerPage(5)
            .setEntryConsumer((pos, entry)->fail("no entry should be consumed for this case"))
            .setHeaderLanguageKey("pagination.header")
            .setFooterLanguageKey("pagination.footer").build();
  }

  @Test
  void emptyEntriesReportOnePageAndConsumeNothing() {

    final Pagination<String> pagination = new Pagination<>(options(List.of(), 1));

    assertEquals(1, pagination.totalPages());
    assertEquals(1, pagination.page());
    final CommandSender sender = mock(CommandSender.class);
    assertDoesNotThrow(()->pagination.printEntries(sender));
  }

  @Test
  void emptyEntriesClampOutOfRangeRequestToPageOne() {

    // the guard must not depend on the caller passing a sane page number
    final Pagination<String> pagination = new Pagination<>(options(List.of(), 5));

    assertEquals(1, pagination.page());
    assertEquals(1, pagination.totalPages());
    assertDoesNotThrow(()->pagination.printEntries(mock(CommandSender.class)));
  }

  @Test
  void emptyListVariantsBehaveTheSame() {

    assertSame(1, new Pagination<>(options(new ArrayList<>(), 1)).totalPages());
    assertSame(1, new Pagination<>(options(List.copyOf(List.of()), 3)).totalPages());
  }

  @Test
  void nonEmptySizingUnchanged() {

    // exact page boundary and rounding stay as before the max(1, ...) floor
    assertEquals(3, new Pagination<>(options(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"), 1)).totalPages());
    assertEquals(1, new Pagination<>(options(List.of("a", "b", "c", "d", "e"), 1)).totalPages());
    assertEquals(2, new Pagination<>(options(List.of("a", "b", "c", "d", "e", "f"), 1)).totalPages());
    // out-of-range requests still clamp to the last real page
    assertEquals(3, new Pagination<>(options(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"), 99)).page());
  }
}
