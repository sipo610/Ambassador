package org.adde0109.ambassador.velocity.client;

import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

public class OutboundSuccessHolder extends ChannelOutboundHandlerAdapter {

  private ServerLoginSuccessPacket packet;
  private ChannelHandlerContext ctx;

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    this.ctx = ctx;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if ((msg instanceof ServerLoginSuccessPacket packet)) {
      this.packet = packet;
    } else {
      ctx.write(msg, promise);
    }
  }

  public ChannelFuture sendPacket(ConnectedPlayer player) {
    if (packet == null) {
      packet = new ServerLoginSuccessPacket();
    }
    packet.setUsername(player.getUsername());
    packet.setProperties(player.getGameProfileProperties());
    packet.setUuid(player.getUniqueId());
    return sendPacket();
  }

  public ChannelFuture sendPacket() {
    if (packet == null) {
      return ctx.newSucceededFuture();
    }
    ChannelPromise promise = ctx.newPromise();
    ctx.write(packet, promise);
    ctx.flush();
    return promise;
  }
}
