package org.adde0109.ambassador.forge;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.ModInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.BackendConnectionPhases;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ClientConnectionPhase;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.kyori.adventure.text.Component;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.forge.packet.Context;
import org.adde0109.ambassador.forge.packet.GenericForgeLoginWrapperPacket;
import org.adde0109.ambassador.forge.packet.IForgeLoginWrapperPacket;
import org.adde0109.ambassador.forge.packet.ModListReplyPacket;
import org.adde0109.ambassador.velocity.client.ClientPacketQueue;
import org.adde0109.ambassador.velocity.client.FML2CRPMResetCompleteDecoder;
import org.adde0109.ambassador.velocity.client.OutboundSuccessHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public enum VelocityForgeClientConnectionPhase implements ClientConnectionPhase {

  NOT_STARTED {
    @Override
    VelocityForgeClientConnectionPhase nextPhase() {
      return IN_PROGRESS;
    }

    @Override
    public void complete(ConnectedPlayer player) {
      // When no handshake has taken place, test if the client supports CRP.
      ClientResetType.CRP.doReset(player);
    }
  },
  IN_PROGRESS {
  },
  WAITING_RESET {
    @Override
    void onTransitionToNewPhase(ConnectedPlayer player) {
      // We unregister so no plugin sees this client while the client is being reset.
      ((VelocityServer) Ambassador.getInstance().server).unregisterConnection(player);
      player.getConnection().getChannel().pipeline().addAfter(Connections.MINECRAFT_ENCODER,
              ForgeConstants.LOGIN_PACKET_QUEUE, new ClientPacketQueue(StateRegistry.PLAY));
      if (player.getConnection().getChannel().pipeline().get(ForgeConstants.PLUGIN_PACKET_QUEUE) == null) {
        player.getConnection().getChannel().pipeline().addAfter(Connections.MINECRAFT_ENCODER,
                ForgeConstants.PLUGIN_PACKET_QUEUE, new ClientPacketQueue(StateRegistry.LOGIN));
      }
    }

    @Override
    public boolean handle(ConnectedPlayer player, IForgeLoginWrapperPacket msg, VelocityServerConnection server) {
      if (msg.getContext().getResponseID() == 98) {
        // Reset complete.
        player.getConnection().getChannel().pipeline().remove(ForgeConstants.RESET_LISTENER);
        player.setPhase(NOT_STARTED);
        player.getConnection().getChannel().pipeline().remove(ForgeConstants.LOGIN_PACKET_QUEUE);

        if (!(server.getConnection().getType() instanceof ForgeFMLConnectionType)) {
          complete(player, ((Context.ClientContext) msg.getContext()).success() ? ClientResetType.CRP : null);
        }

        if (player.getConnectionInFlight() != null) {
          player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(true);
        }

        Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] crp-reset-ack player={} server={} success={} clientState={} inFlight={}",
                player.getUsername(), server == null ? "<none>" : server.getServerInfo().getName(),
                ((Context.ClientContext) msg.getContext()).success(), player.getConnection().getState(),
                player.getConnectionInFlight() == null ? "<none>" : player.getConnectionInFlight().getClass().getName());

        PendingForgeLoginPacket pending = takePendingResetLoginPacket(player);
        if (pending != null) {
          if (player.isActive() && pending.server() == server) {
            Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] forward-after-crp-ack player={} server={} packet={}",
                    player.getUsername(), pending.server().getServerInfo().getName(), pending.message().getClass().getSimpleName());
            VelocityForgeBackendConnectionPhase.forwardForgeLoginPacketToClient(player, pending.message());
          } else {
            Ambassador.getInstance().logger.warn("Skipping pending forge login packet after reset ACK player={} active={} pendingServer={} currentServer={}",
                    player.getUsername(), player.isActive(), pending.server().getServerInfo().getName(),
                    server == null ? "<none>" : server.getServerInfo().getName());
            pending.server().disconnect();
          }
        }
        return true;
      }
      return false;
    }
  },
  COMPLETE {
    private ClientResetType resetType = ClientResetType.UNKNOWN;

    @Override
    void onTransitionToNewPhase(ConnectedPlayer player) {
      sendLoginSuccessAndSwitchToPlay(player);
    }

    @Override
    public void resetConnectionPhase(ConnectedPlayer player) {
      resetConnectionPhaseFuture(player);
    }

    @Override
    public boolean consideredComplete() {
      return true;
    }

    @Override
    public void complete(ConnectedPlayer player) {
      if (Ambassador.getInstance().config.isDebugMode()) {
        player.sendMessage(Component.text("Not resetting"));
      }
    }

    @Override
    void setResetType(ConnectedPlayer player, ClientResetType resetType) {
      this.resetType = resetType;
      if (Ambassador.getInstance().config.isDebugMode()) {
        if (player.getConnection().getState() == StateRegistry.PLAY
                || player.getConnection().getState() == StateRegistry.CONFIG) {
          player.sendMessage(Component.text("Reset type: " + this.resetType));
        } else {
          Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] reset-type player={} resetType={} clientState={}",
                  player.getUsername(), this.resetType, player.getConnection().getState());
        }
      }
    }

    @Override
    public ClientResetType getResetType() {
      return resetType;
    }
  };

  private static final AttributeKey<PendingForgeLoginPacket> PENDING_RESET_LOGIN_PACKET =
          AttributeKey.valueOf("ambassador.pending-reset-login-packet");

  // TODO: Make a new class linked to each player with these fields instead of having them in this phase class.
  public ForgeHandshake forgeHandshake = new ForgeHandshake();

  public boolean handle(ConnectedPlayer player, IForgeLoginWrapperPacket<Context.ClientContext> msg,
                        VelocityServerConnection server) {
    if (msg.getContext().getChannelName().equals("zeta:main")) {
      forgeHandshake.zetaFlagsPacket = (GenericForgeLoginWrapperPacket<Context.ClientContext>) msg;
    }

    if (msg instanceof ModListReplyPacket replyPacket) {
      ModInfo modInfo = new ModInfo("FML2", replyPacket.getMods().stream()
              .map(v -> new ModInfo.Mod(v, "1")).toList());
      player.setModInfo(modInfo);
      forgeHandshake.setModListReplyPacket(replyPacket);
      if (getResetType() == ClientResetType.UNKNOWN) {
        ClientResetType detectedResetType = detectResetType(replyPacket.getMods(), player);
        setResetType(player, detectedResetType);
        Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] reset-type-from-modlist player={} server={} resetType={}",
                player.getUsername(), server.getServerInfo().getName(), detectedResetType);
      }
      Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] client-modlist-reply player={} server={} mods={} channels={} registries={} hasCRP={} sampleMods={}",
              player.getUsername(), server.getServerInfo().getName(), replyPacket.getMods().size(),
              replyPacket.getChannels().size(), replyPacket.getRegistries().size(),
              replyPacket.getMods().stream().anyMatch(v -> v.equalsIgnoreCase("clientresetpacket")),
              describeMods(replyPacket.getMods()));
      if (!(server.getConnection().getType() instanceof ForgeFMLConnectionType)) {
        complete(player);
        player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(true);
        return true;
      }
      replyPacket.getChannels().put(MinecraftChannelIdentifier.from("ambassador:commands"), "1");
    }

    player.getConnectionInFlight().getConnection().write(msg);
    player.setPhase(nextPhase());
    nextPhase().forgeHandshake = this.forgeHandshake;
    return true;
  }

  public void complete(ConnectedPlayer player) {
    completeFuture(player, null);
  }

  public void complete(ConnectedPlayer player, ClientResetType resetType) {
    completeFuture(player, resetType);
  }

  public ChannelFuture completeFuture(ConnectedPlayer player) {
    return completeFuture(player, null);
  }

  public ChannelFuture completeFuture(ConnectedPlayer player, ClientResetType resetType) {
    player.setPhase(COMPLETE);
    ChannelFuture successFuture = sendLoginSuccessAndSwitchToPlay(player);
    COMPLETE.forgeHandshake = forgeHandshake;
    if (resetType != null) {
      COMPLETE.setResetType(player, resetType);
    }

    if (Ambassador.getInstance().config.isDebugMode()) {
      player.sendMessage(Component.text("Forge handshake complete"));
    }
    return successFuture;
  }

  void onTransitionToNewPhase(ConnectedPlayer player) {
  }

  VelocityForgeClientConnectionPhase nextPhase() {
    return this;
  }

  @Override
  public boolean consideredComplete() {
    return false;
  }

  public ChannelFuture resetConnectionPhaseFuture(ConnectedPlayer player) {
    return getResetType().doReset(player);
  }

  public ChannelFuture resetConnectionPhaseAndForwardAfterAck(ConnectedPlayer player,
                                                              VelocityServerConnection server,
                                                              IForgeLoginWrapperPacket<?> message) {
    Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] waiting-crp-reset-ack player={} server={} packet={} currentState={} resetType={}",
            player.getUsername(), server.getServerInfo().getName(), message.getClass().getSimpleName(),
            player.getConnection().getState(), getResetType());
    setPendingResetLoginPacket(player, new PendingForgeLoginPacket(server, message));

    ChannelFuture resetFuture = resetConnectionPhaseFuture(player);
    resetFuture.addListener(future -> {
      if (!future.isSuccess()) {
        takePendingResetLoginPacket(player);
        Ambassador.getInstance().logger.warn("Failed to write CRP reset before forwarding forge login packet player={} server={}",
                player.getUsername(), server.getServerInfo().getName(), future.cause());
        server.disconnect();
        return;
      }

      player.getConnection().getChannel().eventLoop().schedule(() -> {
        PendingForgeLoginPacket pending = peekPendingResetLoginPacket(player);
        if (pending != null && pending.server() == server) {
          takePendingResetLoginPacket(player);
          Ambassador.getInstance().logger.warn("Timed out waiting for CRP reset ACK player={} server={}",
                  player.getUsername(), server.getServerInfo().getName());
          server.disconnect();
        }
      }, 10, TimeUnit.SECONDS);
    });
    return resetFuture;
  }

  public ClientResetType getResetType() {
    return COMPLETE.getResetType();
  }

  private ClientResetType getResetType(ConnectedPlayer player) {
    if (Ambassador.getInstance().config.isDebugMode()) {
      player.sendMessage(Component.text("Scanning modlist for client reset mods"));
    }
    if (player.getModInfo().isPresent()) {
      return detectResetType(player.getModInfo().get().getMods().stream()
              .map(ModInfo.Mod::getId).toList(), player);
    }
    return ClientResetType.NONE;
  }

  private ClientResetType detectResetType(List<String> modIds, ConnectedPlayer player) {
    if (modIds.stream().anyMatch(mod -> mod.equalsIgnoreCase("clientresetpacket"))) {
      return ClientResetType.CRP;
    }
    if (Ambassador.getInstance().config.getServerSwitchCancellationTime() >= 0
            && player.getVirtualHost().isPresent()
            && modIds.stream().anyMatch(mod -> mod.equalsIgnoreCase("serverredirect")
            || mod.equalsIgnoreCase("srvredirect:red"))) {
      return ClientResetType.SR;
    }
    return ClientResetType.NONE;
  }

  void setResetType(ConnectedPlayer player, ClientResetType resetType) {
    COMPLETE.setResetType(player, resetType);
  }

  public void updateResetType(ConnectedPlayer player) {
    COMPLETE.setResetType(player, getResetType(player));
  }

  private static boolean isOutPreBridge(ConnectedPlayer player) {
    return player.getConnectionInFlight() != null
            && player.getConnectionInFlight().getClass().getName().startsWith("icu.h2l.login.vServer.outpre.");
  }

  private static ChannelFuture sendLoginSuccessAndSwitchToPlay(ConnectedPlayer player) {
    MinecraftConnection connection = player.getConnection();
    OutboundSuccessHolder successHolder = (OutboundSuccessHolder) connection.getChannel().pipeline()
            .get(ForgeConstants.SERVER_SUCCESS_LISTENER);
    Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] send-login-success player={} uuid={} outpreBridge={} holderPresent={} clientState={} active={}",
            player.getUsername(), player.getUniqueId(), isOutPreBridge(player), successHolder != null,
            connection.getState(), connection.getChannel().isActive());
    ChannelFuture successFuture = successHolder != null
            ? successHolder.sendPacket(player)
            : connection.getChannel().newSucceededFuture();
    successFuture.addListener(future -> {
      if (!future.isSuccess()) {
        Ambassador.getInstance().debugWarn("[AMB-HZL-DEBUG] send-login-success-failed player={} uuid={}",
                player.getUsername(), player.getUniqueId(), future.cause());
        return;
      }
      if (!connection.getChannel().isActive()) {
        return;
      }
      Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] login-success-written player={} uuid={} switchingState=PLAY",
              player.getUsername(), player.getUniqueId());
      connection.setState(StateRegistry.PLAY);
      if (player.getConnection().getChannel().pipeline().get(ForgeConstants.PLUGIN_PACKET_QUEUE) != null) {
        player.getConnection().getChannel().pipeline().remove(ForgeConstants.PLUGIN_PACKET_QUEUE);
      }
      if (!isOutPreBridge(player)) {
        ((VelocityServer) Ambassador.getInstance().server).registerConnection(player);
      }
    });
    return successFuture;
  }

  private static String describeMods(List<String> mods) {
    String shown = mods.stream().limit(20).collect(Collectors.joining(", "));
    if (mods.size() > 20) {
      shown += " ... +" + (mods.size() - 20);
    }
    return shown;
  }

  private static void setPendingResetLoginPacket(ConnectedPlayer player, PendingForgeLoginPacket packet) {
    player.getConnection().getChannel().attr(PENDING_RESET_LOGIN_PACKET).set(packet);
  }

  private static PendingForgeLoginPacket peekPendingResetLoginPacket(ConnectedPlayer player) {
    return player.getConnection().getChannel().attr(PENDING_RESET_LOGIN_PACKET).get();
  }

  private static PendingForgeLoginPacket takePendingResetLoginPacket(ConnectedPlayer player) {
    Attribute<PendingForgeLoginPacket> attr = player.getConnection().getChannel().attr(PENDING_RESET_LOGIN_PACKET);
    PendingForgeLoginPacket packet = attr.get();
    attr.set(null);
    return packet;
  }

  private record PendingForgeLoginPacket(VelocityServerConnection server, IForgeLoginWrapperPacket<?> message) {
  }

  public enum ClientResetType {
    UNKNOWN,
    NONE,
    CRP {
      @Override
      ChannelFuture doReset(ConnectedPlayer player) {
        MinecraftConnection connection = player.getConnection();
        Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] crp-reset-send player={} state={} connectedServer={} inFlight={}",
                player.getUsername(), connection.getState(),
                player.getConnectedServer() == null ? "<none>" : player.getConnectedServer().getServerInfo().getName(),
                player.getConnectionInFlight() == null ? "<none>" : player.getConnectionInFlight().getClass().getName());

        VelocityServerConnection oldServer = player.getConnectedServer();
        if (oldServer != null) {
          Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] crp-mark-old-backend-transition player={} oldServer={}",
                  player.getUsername(), oldServer.getServerInfo().getName());
          oldServer.setConnectionPhase(BackendConnectionPhases.IN_TRANSITION);
          player.setConnectedServer(null);
          oldServer.disconnect();
        }
        if (player.getConnectionInFlight() != null) {
          player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(false);
        }

        ChannelFuture resetFuture;
        if (connection.getState() == StateRegistry.PLAY || connection.getState() == StateRegistry.CONFIG) {
          resetFuture = connection.write(new PluginMessagePacket("fml:handshake",
                  Unpooled.wrappedBuffer(ForgeHandshakeUtils.generatePluginResetPacket())));
          resetFuture.addListener(future -> {
            if (future.isSuccess() && connection.getChannel().isActive()) {
              connection.setState(StateRegistry.LOGIN);
            }
          });
        } else {
          resetFuture = connection.write(new LoginPluginMessagePacket(98, "fml:loginwrapper",
                  Unpooled.wrappedBuffer(ForgeHandshakeUtils.generateResetPacket())));
        }

        if (connection.getChannel().pipeline().get(ForgeConstants.RESET_LISTENER) == null) {
          connection.getChannel().pipeline().addBefore(Connections.MINECRAFT_DECODER,
                  ForgeConstants.RESET_LISTENER, new FML2CRPMResetCompleteDecoder());
        }

        player.setPhase(WAITING_RESET);
        WAITING_RESET.onTransitionToNewPhase(player);
        return resetFuture == null ? connection.getChannel().newSucceededFuture() : resetFuture;
      }
    },
    SR {
      @Override
      ChannelFuture doReset(ConnectedPlayer player) {
        ByteBuf buf = Unpooled.buffer();
        ProtocolUtils.writeVarInt(buf, 0);
        buf.writeBytes((player.getVirtualHost().get().getHostName() + ":"
                + player.getVirtualHost().get().getPort()).getBytes(StandardCharsets.UTF_8));
        ChannelFuture resetFuture = player.getConnection().write(new PluginMessagePacket("srvredirect:red", buf));
        Ambassador.getInstance().reconnectSwitchPlayer(player);
        return resetFuture == null ? player.getConnection().getChannel().newSucceededFuture() : resetFuture;
      }
    };

    ChannelFuture doReset(ConnectedPlayer player) {
      return player.getConnection().getChannel().newSucceededFuture();
    }
  }
}