package com.ghostchu.quickshop.shop.display.virtual.packet.packetevents;

import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import org.jetbrains.annotations.NotNull;

/**
 * Reads the leading chunk coordinates from a CHUNK_DATA packet buffer without parsing
 * the chunk. Constructing a WrapperPlayServerChunkData and calling getColumn() runs the
 * full ChunkReader pass — every vertical section's paletted block and biome storage,
 * commonly a six-figure byte count per packet — on the netty thread, for every chunk
 * packet sent to every player, even though the display-item lookup only needs the
 * column's x/z. This peek reads exactly two ints and restores the reader index, so it
 * is O(1) regardless of chunk size and leaves the buffer untouched for other listeners.
 */
public final class ChunkPacketPeek {

  public record Coords(int x, int z) {

  }

  private ChunkPacketPeek() {

  }

  public static @NotNull Coords peek(@NotNull final Object buffer) {

    final int readerIndex = ByteBufHelper.readerIndex(buffer);
    try {
      return new Coords(ByteBufHelper.readInt(buffer), ByteBufHelper.readInt(buffer));
    } finally {
      ByteBufHelper.readerIndex(buffer, readerIndex);
    }
  }
}
