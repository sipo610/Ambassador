package org.adde0109.ambassador.velocity.client;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.VoidChannelPromise;
import org.adde0109.ambassador.Ambassador;

public class ClientConnectionDebugHandler extends ChannelDuplexHandler {

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (Ambassador.getInstance().config.isDebugMode()) {
      logClientState(ctx, "client-exception", null, cause);
    }
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (Ambassador.getInstance().config.isDebugMode()) {
      logClientState(ctx, "client-channel-inactive", null, null);
    }
    super.channelInactive(ctx);
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    if (Ambassador.getInstance().config.isDebugMode()) {
      logClientState(ctx, "client-close-requested", null, null);
    }
    super.close(ctx, promise);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (Ambassador.getInstance().config.isDebugMode() && !(promise instanceof VoidChannelPromise)) {
      String packetName = msg == null ? "<null>" : msg.getClass().getName();
      promise.addListener(future -> {
        if (!future.isSuccess()) {
          logClientState(ctx, "client-write-failed", packetName, future.cause());
        }
      });
    }
    super.write(ctx, msg, promise);
  }

  private static void logClientState(ChannelHandlerContext ctx, String marker, String packetName, Throwable cause) {
    MinecraftConnection connection = (MinecraftConnection) ctx.pipeline().get(Connections.HANDLER);
    Object association = connection == null ? null : connection.getAssociation();
    if (association instanceof ConnectedPlayer player) {
      String connectedServer = player.getConnectedServer() == null
              ? "<none>"
              : player.getConnectedServer().getServerInfo().getName();
      String inFlight = player.getConnectionInFlight() == null
              ? "<none>"
              : player.getConnectionInFlight().getServerInfo().getName();
      Ambassador.getInstance().debugWarn(
              "[AMB-HZL-DEBUG] {} player={} packet={} clientState={} clientHandler={} phase={} active={} connectedServer={} inFlight={} cause={}",
              marker, player.getUsername(), packetName == null ? "<none>" : packetName,
              connection.getState(),
              connection.getActiveSessionHandler() == null ? "<none>" : connection.getActiveSessionHandler().getClass().getName(),
              player.getPhase(), player.isActive(), connectedServer, inFlight, describeThrowable(cause));
      return;
    }
    Ambassador.getInstance().debugWarn("[AMB-HZL-DEBUG] {} clientAssociation={} packet={} cause={}",
            marker, association == null ? "<none>" : association.getClass().getName(),
            packetName == null ? "<none>" : packetName, describeThrowable(cause));
  }

  private static String describeThrowable(Throwable cause) {
    if (cause == null) {
      return "<none>";
    }
    return cause.getClass().getName() + ": " + String.valueOf(cause.getMessage());
  }
}