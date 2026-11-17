package com.bettercontent.spaceflight.probe;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual cached Netty, no Minecraft bootstrap. Does not prove Forge login or loaded physics. */
class ObserverTransportTest {
    @Test void activatesTheNamedConnectionHandlerOnABoundChannel() {
        AtomicReference<Channel> bound = new AtomicReference<>();
        var handler = new ChannelInboundHandlerAdapter() {
            @Override public void channelActive(ChannelHandlerContext context) {
                bound.set(context.channel());
                context.fireChannelActive();
            }
        };
        var channel = ObserverTransport.open(handler);
        try {
            assertSame(channel, bound.get());
            assertSame(handler, channel.pipeline().get("packet_handler"));
            assertTrue(channel.isActive());
            assertTrue(channel.isOpen());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void dropsAndReleasesOutboundBuffersInsteadOfAccumulatingThem() {
        var channel = ObserverTransport.open(new ChannelInboundHandlerAdapter());
        try {
            var buffer = Unpooled.buffer().writeInt(42);
            assertEquals(1, buffer.refCnt());
            var result = channel.writeAndFlush(buffer);
            assertTrue(result.isSuccess());
            assertEquals(0, buffer.refCnt());
            assertTrue(channel.outboundMessages().isEmpty());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void acceptsNonReferenceCountedPacketsWithoutClaimingDelivery() {
        var channel = ObserverTransport.open(new ChannelInboundHandlerAdapter());
        try {
            assertTrue(channel.writeAndFlush(new Object()).isSuccess());
            assertTrue(channel.outboundMessages().isEmpty());
            assertTrue(channel.inboundMessages().isEmpty());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void cleanupIsIdempotentAndClosesTheBoundChannel() {
        var channel = ObserverTransport.open(new ChannelInboundHandlerAdapter());
        channel.finishAndReleaseAll();
        channel.finishAndReleaseAll();
        assertFalse(channel.isActive());
        assertFalse(channel.isOpen());
        assertTrue(channel.inboundMessages().isEmpty());
        assertTrue(channel.outboundMessages().isEmpty());
    }
}
