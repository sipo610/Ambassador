package org.adde0109.ambassador.velocity.backend;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.forge.*;

public class ForgeLoginSessionHandler implements MinecraftSessionHandler {

  private final MinecraftSessionHandler original;
  private final VelocityServerConnection serverConnection;
  private final VelocityServer server;

  public ForgeLoginSessionHandler(MinecraftSessionHandler original, VelocityServerConnection serverConnection, VelocityServer server) {
    this.original = original;
    this.serverConnection = serverConnection;
    this.server = server;
  }

  @Override
  public boolean handle(ServerLoginSuccessPacket packet) {
    ConnectedPlayer player = serverConnection.getPlayer();
    Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] backend-login-success packet player={} backend={} packetUser={} packetUuid={} backendPhase={} clientPhase={} clientState={}",
            player.getUsername(), serverConnection.getServerInfo().getName(), packet.getUsername(), packet.getUuid(),
            serverConnection.getPhase(), player.getPhase(), player.getConnection().getState());

    if ((serverConnection.getPhase() instanceof VelocityForgeBackendConnectionPhase phase)) {
      phase.onLoginSuccess(serverConnection,serverConnection.getPlayer());
    }

    Channel backendChannel = serverConnection.getConnection() == null
            ? null
            : serverConnection.getConnection().getChannel();
    if (backendChannel != null) {
      backendChannel.config().setAutoRead(false);
      Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] backend-autoread-paused player={} backend={} reason=client-login-success-gate",
              player.getUsername(), serverConnection.getServerInfo().getName());
    }

    original.handle(packet);  //Can lead to disconnect.
    BackendPlaySessionDebugWrapper.install(serverConnection);

    //If we are still connected after handling that package.
    if (serverConnection.getConnection() != null) {
      Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] backend-login-success-original-ok player={} backend={} active={} clientState={} connectedServer={} inFlight={}",
              player.getUsername(), serverConnection.getServerInfo().getName(), player.isActive(),
              player.getConnection().getState(),
              player.getConnectedServer() == null ? "<none>" : player.getConnectedServer().getServerInfo().getName(),
              player.getConnectionInFlight() == null ? "<none>" : player.getConnectionInFlight().getClass().getName());

      VelocityForgeClientConnectionPhase clientPhase = (VelocityForgeClientConnectionPhase) player.getPhase();
      ChannelFuture successFuture = clientPhase.completeFuture(player);
      successFuture.addListener(future -> {
        if (backendChannel != null && backendChannel.isActive()) {
          backendChannel.config().setAutoRead(true);
          Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] backend-autoread-resumed player={} backend={} success={}",
                  player.getUsername(), serverConnection.getServerInfo().getName(), future.isSuccess());
        }
        if (!future.isSuccess()) {
          Ambassador.getInstance().debugWarn("[AMB-HZL-DEBUG] backend-login-success-gate-failed player={} backend={}",
                  player.getUsername(), serverConnection.getServerInfo().getName(), future.cause());
          serverConnection.disconnect();
        }
      });
    } else {
      Ambassador.getInstance().debugWarn("[AMB-HZL-DEBUG] backend-login-success-original-closed player={} backend={}",
              player.getUsername(), serverConnection.getServerInfo().getName());
      if (backendChannel != null && backendChannel.isActive()) {
        backendChannel.config().setAutoRead(true);
      }
    }

    return true;
  }



  @Override
  public boolean handle(DisconnectPacket packet) {
    Ambassador.getInstance().debugWarn("[AMB-HZL-DEBUG] backend-disconnect-packet player={} backend={} packet={}",
            serverConnection.getPlayer().getUsername(), serverConnection.getServerInfo().getName(), packet);
    return original.handle(packet);
  }

  @Override
  public void disconnected() {
      original.disconnected();
  }

  public void handleGeneric(MinecraftPacket packet) {
    if (!packet.handle(original))
      original.handleGeneric(packet);
  }

  public MinecraftSessionHandler getOriginal() {
    return this.original;
  }
}
