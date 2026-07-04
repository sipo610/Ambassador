package org.adde0109.ambassador.velocity.backend;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.channel.*;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.forge.ForgeConstants;
import org.adde0109.ambassador.forge.ForgeFMLConnectionType;
import org.adde0109.ambassador.forge.pipeline.ForgeLoginWrapperCodec;
import org.adde0109.ambassador.forge.pipeline.ForgeLoginWrapperHandler;
import org.jetbrains.annotations.NotNull;

public class VelocityForgeBackendHandshakeHandler extends ChannelInboundHandlerAdapter {

  private final VelocityServer server;

  public VelocityForgeBackendHandshakeHandler(VelocityServer server) {
    this.server = server;
  }

  @Override
  public void channelActive(@NotNull ChannelHandlerContext ctx) throws Exception {
    MinecraftConnection connection = (MinecraftConnection) ctx.pipeline().get(Connections.HANDLER);
    Object association = connection.getAssociation();

    ctx.pipeline().remove(this);

    if (association instanceof VelocityServerConnection serverConnection) {
      ConnectedPlayer player = serverConnection.getPlayer();
      if (player.getConnection().getType() instanceof ForgeFMLConnectionType) {
        MinecraftSessionHandler sessionHandler = connection.getActiveSessionHandler();
        if (sessionHandler == null) {
          ctx.pipeline().fireChannelActive();
          return;
        }

        Ambassador.getInstance().trace("[HZL-OUTPRE] backend forge wrapper installing server={} player={} activeHandler={} channel={}", serverConnection.getServerInfo().getName(), player.getUsername(), sessionHandler.getClass().getName(), connection.getChannel());
        ForgeLoginSessionHandler forgeLoginSessionHandler = new ForgeLoginSessionHandler(sessionHandler, serverConnection, server);
        connection.setActiveSessionHandler(StateRegistry.LOGIN, forgeLoginSessionHandler);

        serverConnection.getConnection().getChannel().pipeline().addBefore(
                Connections.HANDLER,
                ForgeConstants.FORGE_HANDSHAKE_DECODER, new ForgeLoginWrapperCodec(
                        player.getConnection().getType() == ForgeConstants.ForgeFML3));
        serverConnection.getConnection().getChannel().pipeline().addAfter(
                ForgeConstants.FORGE_HANDSHAKE_DECODER,
                ForgeConstants.FORGE_HANDSHAKE_HANDLER, new ForgeLoginWrapperHandler(serverConnection));
      }
    }

    ctx.pipeline().fireChannelActive();
  }
}