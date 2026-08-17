package com.ghostchu.quickshop.shop.display.virtual.packet.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.NettyManager;
import io.github.retrooper.packetevents.impl.netty.buffer.ByteBufOperatorImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the CHUNK_DATA coordinate peek: it must read exactly the two
 * leading ints of the packet buffer and leave the reader index (and every payload byte)
 * untouched, whatever the chunk payload size behind them.
 */
class ChunkPacketPeekTest {

  @BeforeAll
  static void bridgeByteBufHelper() {

    // ByteBufHelper's static methods route through the PacketEvents API; give them the
    // real netty ByteBufOperator behind a minimal mock API
    final var api = mock(com.github.retrooper.packetevents.PacketEventsAPI.class);
    final NettyManager nettyManager = mock(NettyManager.class);
    when(api.getNettyManager()).thenReturn(nettyManager);
    lenient().when(nettyManager.getByteBufOperator()).thenReturn(new ByteBufOperatorImpl());
    PacketEvents.setAPI(api);
  }

  private static ByteBuf chunkBuffer(final int x, final int z, final int payloadBytes) {

    final ByteBuf buffer = Unpooled.buffer(8 + payloadBytes);
    buffer.writeInt(x);
    buffer.writeInt(z);
    final byte[] payload = new byte[payloadBytes];
    ThreadLocalRandom.current().nextBytes(payload);
    buffer.writeBytes(payload);
    return buffer;
  }

  @Test
  void readsLeadingCoordinates() {

    final ByteBuf buffer = chunkBuffer(-123, 456, 32);
    final ChunkPacketPeek.Coords coords = ChunkPacketPeek.peek(buffer);
    assertEquals(-123, coords.x());
    assertEquals(456, coords.z());
    assertEquals(0, buffer.readerIndex());
    buffer.release();
  }

  @Test
  void restoresReaderIndexForAnyPayloadSize() {

    for(final int payload : new int[]{0, 1, 64, 100_000}) {
      final ByteBuf buffer = chunkBuffer(7, -7, payload);
      buffer.readerIndex(0);
      ChunkPacketPeek.peek(buffer);
      assertEquals(0, buffer.readerIndex(), "reader index must be restored (payload=" + payload + ')');
      // and the peek must not modify a single payload byte
      assertEquals(7, buffer.readInt());
      assertEquals(-7, buffer.readInt());
      buffer.release();
    }
  }

  @Test
  void readsFromCurrentIndexAndRestoresIt() {

    final ByteBuf buffer = chunkBuffer(10, 20, 16);
    buffer.readerIndex(4); // sequential-read semantics: reads the ints AT the index
    final ChunkPacketPeek.Coords coords = ChunkPacketPeek.peek(buffer);
    assertEquals(20, coords.x()); // the int at bytes 4-7
    assertEquals(4, buffer.readerIndex());
    buffer.release();
  }
}
