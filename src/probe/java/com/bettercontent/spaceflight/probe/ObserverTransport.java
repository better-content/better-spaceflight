package com.bettercontent.spaceflight.probe;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** Bound synthetic transport ONLY. No client, decoding, rendering or packet-delivery proof. */
final class ObserverTransport {
    private ObserverTransport() {}

    static EmbeddedChannel open(ChannelHandler connection) {
        return new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                channel.pipeline().addLast("spaceflight_probe_sink", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                        // Never accumulate discarded chunk/custom-payload buffers in the fixture.
                        ReferenceCountUtil.release(message);
                        promise.setSuccess();
                    }
                });
                // Forge's real login filter requires a bound channel and this standard handler name.
                channel.pipeline().addLast("packet_handler", connection);
            }
        });
    }
}
