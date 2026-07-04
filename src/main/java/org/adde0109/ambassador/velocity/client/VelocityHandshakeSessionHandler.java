package org.adde0109.ambassador.velocity.client;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.HandshakeSessionHandler;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.forge.ForgeConstants;
import org.adde0109.ambassador.forge.pipeline.ForgeLoginWrapperCodec;
import org.adde0109.ambassador.forge.pipeline.ForgeLoginWrapperHandler;

public class VelocityHandshakeSessionHandler extends HandshakeSessionHandler  {
  private final HandshakeSessionHandler original;
  private final MinecraftConnection connection;

  public VelocityHandshakeSessionHandler(HandshakeSessionHandler original, MinecraftConnection connection, VelocityServer server) {
    super(connection, server);
    this.original = original;
    this.connection = connection;
  }

  @Override
  public boolean handle(HandshakePacket handshake) {
    handshake.handle(original);
    if (connection.getType() == ConnectionTypes.VANILLA) {
      final String[] markerSplit = handshake.getServerAddress().split("\0");
      if (connection.getState() == StateRegistry.LOGIN && markerSplit.length > 1 && markerSplit[1].startsWith("FML")) {
        switch (markerSplit[1]) {
          case "FML2":
            connection.setType(ForgeConstants.ForgeFML2);
            break;
          case "FML3":
            connection.setType(ForgeConstants.ForgeFML3);
            break;
        }

        Ambassador.getInstance().trace("[HZL-OUTPRE] client FML marker detected type={} state={} channel={}", markerSplit[1], connection.getState(), connection.getChannel());
        ChannelPipeline pipeline = connection.getChannel().pipeline();
        if (pipeline.get(ForgeConstants.SERVER_SUCCESS_LISTENER) == null) {
          pipeline.addAfter(Connections.MINECRAFT_ENCODER, ForgeConstants.SERVER_SUCCESS_LISTENER, new OutboundSuccessHolder());
        }
        if (pipeline.get(ForgeConstants.PLUGIN_PACKET_QUEUE) == null) {
          pipeline.addAfter(Connections.MINECRAFT_ENCODER, ForgeConstants.PLUGIN_PACKET_QUEUE, new ClientPacketQueue(StateRegistry.LOGIN));
        }
        if (pipeline.get(ForgeConstants.FORGE_HANDSHAKE_DECODER) == null) {
          pipeline.addBefore(
                  Connections.HANDLER,
                  ForgeConstants.FORGE_HANDSHAKE_DECODER,
                  new ForgeLoginWrapperCodec(connection.getType() == ForgeConstants.ForgeFML3));
          pipeline.addAfter(
                  ForgeConstants.FORGE_HANDSHAKE_DECODER,
                  ForgeConstants.FORGE_HANDSHAKE_HANDLER,
                  new ForgeLoginWrapperHandler(connection));
        }
      }
    }
    return true;
  }


  @Override
  public void handleGeneric(MinecraftPacket packet) {
    original.handleGeneric(packet);
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    original.handleUnknown(buf);
  }
}
