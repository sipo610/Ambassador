package org.adde0109.ambassador.velocity.backend;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.chat.SystemChatPacket;
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.adde0109.ambassador.Ambassador;

public final class BackendPlaySessionDebugWrapper implements InvocationHandler {

  private static final Field ACTIVE_SESSION_HANDLER_FIELD = findField("activeSessionHandler");
  private static final Field SESSION_HANDLERS_FIELD = findField("sessionHandlers");
  private static final int PACKET_LIMIT = 140;
  private static final long PACKET_WINDOW_NANOS = 15_000_000_000L;

  private final MinecraftSessionHandler original;
  private final VelocityServerConnection serverConnection;
  private final long startNanos = System.nanoTime();
  private int packetCount;

  private BackendPlaySessionDebugWrapper(MinecraftSessionHandler original,
                                         VelocityServerConnection serverConnection) {
    this.original = original;
    this.serverConnection = serverConnection;
  }

  public static void install(VelocityServerConnection serverConnection) {
    if (!Ambassador.getInstance().config.isDebugMode() || serverConnection.getConnection() == null) {
      return;
    }

    MinecraftConnection connection = serverConnection.getConnection();
    MinecraftSessionHandler current = connection.getActiveSessionHandler();
    if (current == null || isWrapped(current)) {
      return;
    }

    MinecraftSessionHandler wrapped = (MinecraftSessionHandler) Proxy.newProxyInstance(
            MinecraftSessionHandler.class.getClassLoader(),
            new Class<?>[]{MinecraftSessionHandler.class},
            new BackendPlaySessionDebugWrapper(current, serverConnection));

    try {
      ACTIVE_SESSION_HANDLER_FIELD.set(connection, wrapped);
      @SuppressWarnings("unchecked")
      Map<StateRegistry, MinecraftSessionHandler> handlers =
              (Map<StateRegistry, MinecraftSessionHandler>) SESSION_HANDLERS_FIELD.get(connection);
      handlers.put(StateRegistry.PLAY, wrapped);
      Ambassador.getInstance().debugInfo(
              "[AMB-HZL-DEBUG] backend-play-wrapper-installed player={} backend={} originalHandler={} backendState={} clientState={} connectedServer={} inFlight={}",
              serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(),
              current.getClass().getName(), connection.getState(),
              serverConnection.getPlayer().getConnection().getState(), connectedServer(serverConnection),
              inFlight(serverConnection));
    } catch (IllegalAccessException ex) {
      Ambassador.getInstance().debugWarn(
              "[AMB-HZL-DEBUG] backend-play-wrapper-install-failed player={} backend={} originalHandler={} cause={}",
              serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(),
              current.getClass().getName(), describeThrowable(ex));
    }
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    String methodName = method.getName();
    Object firstArg = args == null || args.length == 0 ? null : args[0];

    if (firstArg instanceof MinecraftPacket packet) {
      logPacket(methodName, packet);
    } else if (firstArg instanceof ByteBuf byteBuf) {
      logUnknown(methodName, byteBuf);
    } else if ("disconnected".equals(methodName)) {
      logState("backend-play-disconnected-before", null);
    } else if ("exception".equals(methodName)) {
      Throwable throwable = firstArg instanceof Throwable ? (Throwable) firstArg : null;
      logState("backend-play-exception", throwable);
    } else if ("deactivated".equals(methodName)) {
      logState("backend-play-deactivated", null);
    }

    try {
      Object result = method.invoke(original, args);
      if ("disconnected".equals(methodName)) {
        logState("backend-play-disconnected-after", null);
      } else if ("deactivated".equals(methodName)) {
        logState("backend-play-deactivated-after", null);
        scheduleReinstall();
      } else if (firstArg instanceof DisconnectPacket) {
        logState("backend-play-disconnect-result=" + result, null);
      }
      return result;
    } catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause() == null ? ex : ex.getCause();
      logState("backend-play-wrapper-invoke-failed method=" + methodName, cause);
      throw cause;
    }
  }


  private void scheduleReinstall() {
    MinecraftConnection backendConnection = serverConnection.getConnection();
    if (backendConnection == null || !backendConnection.getChannel().isActive()) {
      return;
    }
    backendConnection.getChannel().eventLoop().execute(() -> {
      MinecraftConnection latestConnection = serverConnection.getConnection();
      if (latestConnection == null || !latestConnection.getChannel().isActive()) {
        return;
      }
      MinecraftSessionHandler latest = latestConnection.getActiveSessionHandler();
      Ambassador.getInstance().debugInfo(
              "[AMB-HZL-DEBUG] backend-play-wrapper-reinstall-check player={} backend={} latestHandler={} backendState={} clientState={} connectedServer={} inFlight={}",
              serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(),
              latest == null ? "<none>" : latest.getClass().getName(), latestConnection.getState(),
              serverConnection.getPlayer().getConnection().getState(), connectedServer(serverConnection),
              inFlight(serverConnection));
      install(serverConnection);
    });
  }
  private void logPacket(String methodName, MinecraftPacket packet) {
    long elapsedNanos = System.nanoTime() - startNanos;
    boolean important = packet instanceof DisconnectPacket
            || packet instanceof SystemChatPacket
            || packet instanceof PluginMessagePacket
            || packet instanceof JoinGamePacket
            || packet instanceof StartUpdatePacket;
    if (packetCount >= PACKET_LIMIT && !important && elapsedNanos > PACKET_WINDOW_NANOS) {
      return;
    }
    packetCount++;
    Ambassador.getInstance().debugInfo(
            "[AMB-HZL-DEBUG] backend-play-handler-packet player={} backend={} count={} elapsedMs={} method={} packet={} detail={} backendState={} backendHandler={} backendPhase={} hasCompletedJoin={} playerActive={} clientState={} connectedServer={} inFlight={}",
            serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(),
            packetCount, elapsedNanos / 1_000_000L, methodName, packet.getClass().getName(),
            describePacket(packet),
            serverConnection.getConnection() == null ? "<none>" : serverConnection.getConnection().getState(),
            serverConnection.getConnection() == null || serverConnection.getConnection().getActiveSessionHandler() == null
                    ? "<none>"
                    : serverConnection.getConnection().getActiveSessionHandler().getClass().getName(),
            serverConnection.getPhase(), serverConnection.hasCompletedJoin(), serverConnection.getPlayer().isActive(),
            serverConnection.getPlayer().getConnection().getState(), connectedServer(serverConnection),
            inFlight(serverConnection));
  }

  private void logUnknown(String methodName, ByteBuf byteBuf) {
    Ambassador.getInstance().debugInfo(
            "[AMB-HZL-DEBUG] backend-play-handler-unknown player={} backend={} method={} readableBytes={} backendState={} clientState={} connectedServer={} inFlight={}",
            serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(), methodName,
            byteBuf.readableBytes(),
            serverConnection.getConnection() == null ? "<none>" : serverConnection.getConnection().getState(),
            serverConnection.getPlayer().getConnection().getState(), connectedServer(serverConnection),
            inFlight(serverConnection));
  }

  private void logState(String marker, Throwable cause) {
    MinecraftConnection backendConnection = serverConnection.getConnection();
    Ambassador.getInstance().debugWarn(
            "[AMB-HZL-DEBUG] {} player={} backend={} backendState={} backendHandler={} backendPhase={} hasCompletedJoin={} playerActive={} clientState={} connectedServer={} inFlight={} channelActive={} autoRead={} writable={} cause={}",
            marker, serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(),
            backendConnection == null ? "<none>" : backendConnection.getState(),
            backendConnection == null || backendConnection.getActiveSessionHandler() == null
                    ? "<none>"
                    : backendConnection.getActiveSessionHandler().getClass().getName(),
            serverConnection.getPhase(), serverConnection.hasCompletedJoin(),
            serverConnection.getPlayer().isActive(), serverConnection.getPlayer().getConnection().getState(),
            connectedServer(serverConnection), inFlight(serverConnection),
            backendConnection != null && backendConnection.getChannel().isActive(),
            backendConnection != null && backendConnection.getChannel().config().isAutoRead(),
            backendConnection != null && backendConnection.getChannel().isWritable(),
            describeThrowable(cause));
  }

  private static boolean isWrapped(MinecraftSessionHandler handler) {
    return Proxy.isProxyClass(handler.getClass())
            && Proxy.getInvocationHandler(handler) instanceof BackendPlaySessionDebugWrapper;
  }

  private static Field findField(String name) {
    try {
      Field field = MinecraftConnection.class.getDeclaredField(name);
      field.setAccessible(true);
      return field;
    } catch (NoSuchFieldException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private static String describePacket(MinecraftPacket packet) {
    try {
      if (packet instanceof SystemChatPacket systemChat) {
        String json = systemChat.getComponent() == null ? "<null>" : systemChat.getComponent().getJson();
        return "type=" + systemChat.getType() + " json=" + truncate(json, 700);
      }
      if (packet instanceof PluginMessagePacket pluginMessage) {
        return "channel=" + pluginMessage.getChannel()
                + " bytes=" + pluginMessage.content().readableBytes();
      }
      if (packet instanceof JoinGamePacket joinGame) {
        return "entityId=" + joinGame.getEntityId()
                + " dimensionInfo=" + truncate(String.valueOf(joinGame.getDimensionInfo()), 300)
                + " viewDistance=" + joinGame.getViewDistance()
                + " simulationDistance=" + joinGame.getSimulationDistance();
      }
      if (packet instanceof DisconnectPacket) {
        return truncate(String.valueOf(packet), 700);
      }
      if (packet instanceof StartUpdatePacket) {
        return "start-config";
      }
    } catch (RuntimeException ex) {
      return "detailError=" + describeThrowable(ex);
    }
    return "";
  }

  private static String connectedServer(VelocityServerConnection serverConnection) {
    return serverConnection.getPlayer().getConnectedServer() == null
            ? "<none>"
            : serverConnection.getPlayer().getConnectedServer().getServerInfo().getName();
  }

  private static String inFlight(VelocityServerConnection serverConnection) {
    return serverConnection.getPlayer().getConnectionInFlight() == null
            ? "<none>"
            : serverConnection.getPlayer().getConnectionInFlight().getServerInfo().getName();
  }

  private static String describeThrowable(Throwable cause) {
    if (cause == null) {
      return "<none>";
    }
    return cause.getClass().getName() + ": " + String.valueOf(cause.getMessage());
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength) + "...";
  }
}