package org.adde0109.ambassador.velocity.backend;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket;
import com.velocitypowered.proxy.protocol.packet.chat.SystemChatPacket;
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket;
import com.velocitypowered.proxy.protocol.util.ByteBufDataInput;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import org.adde0109.ambassador.Ambassador;
import org.jetbrains.annotations.NotNull;

public class BackendPluginMessageDebugHandler extends ChannelDuplexHandler {

  private static final AttributeKey<Integer> PLAY_PACKET_COUNT =
          AttributeKey.valueOf("ambassador.playPacketCount");
  private static final AttributeKey<Long> PLAY_PACKET_START_NANOS =
          AttributeKey.valueOf("ambassador.playPacketStartNanos");
  private static final int PLAY_PACKET_LIMIT = 80;
  private static final long PLAY_PACKET_WINDOW_NANOS = 12_000_000_000L;

  @Override
  public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object msg) throws Exception {
    if (Ambassador.getInstance().config.isDebugMode()) {
      if (msg instanceof MinecraftPacket packet) {
        logBackendPacket(ctx, packet);
      }
      if (msg instanceof PluginMessagePacket packet && isBungeeCordMessage(packet.getChannel())) {
        logBungeeCordMessage(ctx, packet);
      }
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (Ambassador.getInstance().config.isDebugMode()) {
      logBackendState(ctx, "backend-exception", cause);
    }
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (Ambassador.getInstance().config.isDebugMode()) {
      logBackendState(ctx, "backend-channel-inactive", null);
    }
    super.channelInactive(ctx);
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    if (Ambassador.getInstance().config.isDebugMode()) {
      logBackendState(ctx, "backend-close-requested", null);
    }
    super.close(ctx, promise);
  }

  private static boolean isBungeeCordMessage(String channel) {
    return "BungeeCord".equals(channel) || "bungeecord:main".equals(channel);
  }

  private static void logBackendPacket(ChannelHandlerContext ctx, MinecraftPacket packet) {
    if (packet instanceof ServerLoginSuccessPacket) {
      ctx.channel().attr(PLAY_PACKET_COUNT).set(0);
      ctx.channel().attr(PLAY_PACKET_START_NANOS).set(System.nanoTime());
      return;
    }

    Long startNanos = ctx.channel().attr(PLAY_PACKET_START_NANOS).get();
    if (startNanos == null) {
      return;
    }

    long elapsedNanos = System.nanoTime() - startNanos;
    Integer current = ctx.channel().attr(PLAY_PACKET_COUNT).get();
    int count = current == null ? 0 : current;
    boolean important = packet instanceof DisconnectPacket
            || packet instanceof SystemChatPacket
            || packet instanceof StartUpdatePacket;
    if (count >= PLAY_PACKET_LIMIT && !important) {
      if (elapsedNanos > PLAY_PACKET_WINDOW_NANOS) {
        ctx.channel().attr(PLAY_PACKET_START_NANOS).set(null);
      }
      return;
    }

    int nextCount = count + 1;
    ctx.channel().attr(PLAY_PACKET_COUNT).set(nextCount);
    logBackendPlayPacket(ctx, nextCount, elapsedNanos / 1_000_000L, packet);
  }

  private static void logBackendPlayPacket(ChannelHandlerContext ctx, int count, long elapsedMs,
                                           MinecraftPacket packet) {
    MinecraftConnection connection = (MinecraftConnection) ctx.pipeline().get(Connections.HANDLER);
    Object association = connection == null ? null : connection.getAssociation();
    if (association instanceof VelocityServerConnection serverConnection) {
      String connectedServer = serverConnection.getPlayer().getConnectedServer() == null
              ? "<none>"
              : serverConnection.getPlayer().getConnectedServer().getServerInfo().getName();
      String inFlight = serverConnection.getPlayer().getConnectionInFlight() == null
              ? "<none>"
              : serverConnection.getPlayer().getConnectionInFlight().getServerInfo().getName();
      Ambassador.getInstance().debugInfo(
              "[AMB-HZL-DEBUG] backend-play-packet player={} backend={} count={} elapsedMs={} packet={} detail={} backendState={} backendHandler={} backendPhase={} hasCompletedJoin={} clientState={} connectedServer={} inFlight={}",
              serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(),
              count, elapsedMs, packet.getClass().getName(), describePacket(packet),
              connection.getState(),
              connection.getActiveSessionHandler() == null ? "<none>" : connection.getActiveSessionHandler().getClass().getName(),
              serverConnection.getPhase(), serverConnection.hasCompletedJoin(),
              serverConnection.getPlayer().getConnection().getState(), connectedServer, inFlight);
    }
  }

  private static String describePacket(MinecraftPacket packet) {
    try {
      if (packet instanceof SystemChatPacket systemChat) {
        String json = systemChat.getComponent() == null ? "<null>" : systemChat.getComponent().getJson();
        return "type=" + systemChat.getType() + " json=" + truncate(json, 512);
      }
      if (packet instanceof PluginMessagePacket pluginMessage) {
        return "channel=" + pluginMessage.getChannel()
                + " bytes=" + pluginMessage.content().readableBytes();
      }
      if (packet instanceof JoinGamePacket joinGame) {
        return "entityId=" + joinGame.getEntityId()
                + " dimensionInfo=" + truncate(String.valueOf(joinGame.getDimensionInfo()), 256)
                + " viewDistance=" + joinGame.getViewDistance()
                + " simulationDistance=" + joinGame.getSimulationDistance();
      }
      if (packet instanceof DisconnectPacket) {
        return truncate(String.valueOf(packet), 512);
      }
      if (packet instanceof StartUpdatePacket) {
        return "start-config";
      }
    } catch (RuntimeException ex) {
      return "detailError=" + ex.getClass().getName() + ": " + String.valueOf(ex.getMessage());
    }
    return "";
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength) + "...";
  }

  private static void logBungeeCordMessage(ChannelHandlerContext ctx, PluginMessagePacket packet) {
    MinecraftConnection connection = (MinecraftConnection) ctx.pipeline().get(Connections.HANDLER);
    Object association = connection == null ? null : connection.getAssociation();
    String playerName = "<unknown>";
    String serverName = "<unknown>";
    String connectedServer = "<none>";
    String subchannel = "<unreadable>";
    String firstArg = "";
    String secondArg = "";

    if (association instanceof VelocityServerConnection serverConnection) {
      playerName = serverConnection.getPlayer().getUsername();
      serverName = serverConnection.getServerInfo().getName();
      if (serverConnection.getPlayer().getConnectedServer() != null) {
        connectedServer = serverConnection.getPlayer().getConnectedServer().getServerInfo().getName();
      }
    }

    ByteBuf data = packet.content().duplicate();
    try {
      ByteBufDataInput input = new ByteBufDataInput(data);
      subchannel = input.readUTF();
      if ("Connect".equals(subchannel) && data.isReadable()) {
        firstArg = input.readUTF();
      } else if ("ConnectOther".equals(subchannel) && data.isReadable()) {
        firstArg = input.readUTF();
        if (data.isReadable()) {
          secondArg = input.readUTF();
        }
      }
    } catch (RuntimeException ignored) {
      // Keep the probe non-invasive. The original packet is left untouched.
    }

    Ambassador.getInstance().debugInfo(
            "[AMB-HZL-DEBUG] backend-bungee-plugin-message player={} fromServer={} connectedServer={} channel={} subchannel={} arg1={} arg2={}",
            playerName, serverName, connectedServer, packet.getChannel(), subchannel, firstArg, secondArg);
  }

  private static void logBackendState(ChannelHandlerContext ctx, String marker, Throwable cause) {
    MinecraftConnection connection = (MinecraftConnection) ctx.pipeline().get(Connections.HANDLER);
    Object association = connection == null ? null : connection.getAssociation();
    if (association instanceof VelocityServerConnection serverConnection) {
      String connectedServer = serverConnection.getPlayer().getConnectedServer() == null
              ? "<none>"
              : serverConnection.getPlayer().getConnectedServer().getServerInfo().getName();
      String inFlight = serverConnection.getPlayer().getConnectionInFlight() == null
              ? "<none>"
              : serverConnection.getPlayer().getConnectionInFlight().getServerInfo().getName();
      Ambassador.getInstance().debugWarn(
              "[AMB-HZL-DEBUG] {} player={} backend={} backendState={} backendHandler={} backendPhase={} hasCompletedJoin={} playerActive={} clientState={} connectedServer={} inFlight={} channelActive={} autoRead={} writable={} cause={}",
              marker, serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(),
              connection.getState(),
              connection.getActiveSessionHandler() == null ? "<none>" : connection.getActiveSessionHandler().getClass().getName(),
              serverConnection.getPhase(), serverConnection.hasCompletedJoin(), serverConnection.getPlayer().isActive(),
              serverConnection.getPlayer().getConnection().getState(), connectedServer, inFlight,
              ctx.channel().isActive(), ctx.channel().config().isAutoRead(), ctx.channel().isWritable(),
              describeThrowable(cause));
      return;
    }
    Ambassador.getInstance().debugWarn("[AMB-HZL-DEBUG] {} backendAssociation={} cause={}",
            marker, association == null ? "<none>" : association.getClass().getName(), describeThrowable(cause));
  }

  private static String describeThrowable(Throwable cause) {
    if (cause == null) {
      return "<none>";
    }
    return cause.getClass().getName() + ": " + String.valueOf(cause.getMessage());
  }
}