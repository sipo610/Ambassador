package org.adde0109.ambassador.forge;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
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
      Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] WAITING_RESET enter player={} clientState={} inFlight={} connected={}",
              player.getUsername(), player.getConnection().getState(),
              player.getConnectionInFlight() == null ? "null" : player.getConnectionInFlight().getServerInfo().getName(),
              player.getConnectedServer() == null ? "null" : player.getConnectedServer().getServerInfo().getName());
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
        RegisteredServer reconnectTarget = resetReconnectTarget;
        resetReconnectTarget = null;
        boolean keepResetListenerForForgeLogin = server != null && server.getConnection().getType() instanceof ForgeFMLConnectionType;
        boolean keepResetListenerForFreshReconnect = reconnectTarget != null;
        if (!keepResetListenerForForgeLogin && !keepResetListenerForFreshReconnect
                && player.getConnection().getChannel().pipeline().get(ForgeConstants.RESET_LISTENER) != null) {
          player.getConnection().getChannel().pipeline().remove(ForgeConstants.RESET_LISTENER);
        } else if (keepResetListenerForForgeLogin) {
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete keeping raw guard until Forge login success player={} target={}",
                  player.getUsername(), server.getServerInfo().getName());
        } else if (keepResetListenerForFreshReconnect) {
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete keeping raw guard during fresh reconnect wait player={} target={} state={}",
                  player.getUsername(), reconnectTarget.getServerInfo().getName(), player.getConnection().getState());
        }
        player.setPhase(NOT_STARTED);

        if (reconnectTarget != null) {
          player.getConnection().setState(StateRegistry.LOGIN);
          if (player.getConnection().getChannel().pipeline().get(ForgeConstants.LOGIN_PACKET_QUEUE) instanceof ClientPacketQueue loginQueue) {
            loginQueue.discardQueuedOnRemove();
            player.getConnection().getChannel().pipeline().remove(ForgeConstants.LOGIN_PACKET_QUEUE);
            Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete discarded stale LOGIN queue before fresh reconnect player={} target={} state={}",
                    player.getUsername(), reconnectTarget.getServerInfo().getName(), player.getConnection().getState());
          }
          takePendingResetLoginPacket(player);
          player.resetInFlightConnection();
          final int freshReconnectDelayMs = Ambassador.getInstance().config.getCrpFreshReconnectDelayMs();
          final String reconnectTargetName = reconnectTarget.getServerInfo().getName();
          Runnable freshReconnect = () -> {
            if (!player.isActive() || !player.getConnection().getChannel().isActive()) {
              Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete fresh reconnect skipped inactive player={} target={} active={} channelActive={}",
                      player.getUsername(), reconnectTargetName, player.isActive(), player.getConnection().getChannel().isActive());
              return;
            }
            player.getConnection().setState(StateRegistry.LOGIN);
            player.resetInFlightConnection();
            Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete reconnecting fresh target player={} target={} active={} state={} immediate={}",
                    player.getUsername(), reconnectTargetName, player.isActive(), player.getConnection().getState(), freshReconnectDelayMs <= 0);
            player.createConnectionRequest(reconnectTarget).fireAndForget();
            Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete fresh connect request fired player={} target={}",
                    player.getUsername(), reconnectTargetName);
          };
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete scheduling fresh reconnect player={} target={} delayMs={} active={} state={}",
                  player.getUsername(), reconnectTargetName, freshReconnectDelayMs, player.isActive(), player.getConnection().getState());
          if (freshReconnectDelayMs <= 0) {
            freshReconnect.run();
          } else {
            player.getConnection().getChannel().eventLoop().schedule(freshReconnect, freshReconnectDelayMs, TimeUnit.MILLISECONDS);
          }
          return true;
        }

        if (keepResetListenerForForgeLogin) {
          final int resetSettleDelayMs = Ambassador.getInstance().config.getCrpResetSettleDelayMs();
          final String targetName = server == null ? "null" : server.getServerInfo().getName();
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete scheduling delayed LOGIN queue flush player={} delayMs={} beforeState={} target={}",
                  player.getUsername(), resetSettleDelayMs, player.getConnection().getState(), targetName);
          player.getConnection().getChannel().eventLoop().schedule(() -> {
            if (!player.isActive() || !player.getConnection().getChannel().isActive()) {
              Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete delayed flush skipped inactive player={} target={}",
                      player.getUsername(), targetName);
              return;
            }
            Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete delayed flush LOGIN queue player={} beforeState={} target={}",
                    player.getUsername(), player.getConnection().getState(), targetName);
            player.getConnection().setState(StateRegistry.LOGIN);
            if (player.getConnection().getChannel().pipeline().get(ForgeConstants.LOGIN_PACKET_QUEUE) != null) {
              player.getConnection().getChannel().pipeline().remove(ForgeConstants.LOGIN_PACKET_QUEUE);
            } else {
              Ambassador.getInstance().traceWarn("[HZL-OUTPRE][TRACE] reset-complete delayed flush missing LOGIN queue player={} target={}",
                      player.getUsername(), targetName);
            }
            Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete delayed flushed LOGIN queue player={} afterState={} target={}",
                    player.getUsername(), player.getConnection().getState(), targetName);
            if (player.getConnectionInFlight() != null) {
              player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(true);
              Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete delayed resumed inFlight player={} target={} inFlight={}",
                      player.getUsername(), targetName, player.getConnectionInFlight().getServerInfo().getName());
            }
            takePendingResetLoginPacket(player);
          }, resetSettleDelayMs, TimeUnit.MILLISECONDS);
          return true;
        }

        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete flush LOGIN queue player={} beforeState={} target={}",
                player.getUsername(), player.getConnection().getState(), server == null ? "null" : server.getServerInfo().getName());
        player.getConnection().setState(StateRegistry.LOGIN);
        if (player.getConnection().getChannel().pipeline().get(ForgeConstants.LOGIN_PACKET_QUEUE) != null) {
          player.getConnection().getChannel().pipeline().remove(ForgeConstants.LOGIN_PACKET_QUEUE);
        }
        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset-complete flushed LOGIN queue player={} afterState={} target={}",
                player.getUsername(), player.getConnection().getState(), server == null ? "null" : server.getServerInfo().getName());

        if (server != null && !(server.getConnection().getType() instanceof ForgeFMLConnectionType)) {
          player.getConnection().setState(StateRegistry.PLAY);
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] reset target vanilla, state LOGIN->PLAY player={} target={}",
                  player.getUsername(), server.getServerInfo().getName());
          complete(player, ((Context.ClientContext) msg.getContext()).success() ? ClientResetType.CRP : null);
        }

        if (player.getConnectionInFlight() != null) {
          player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(true);
        }

        Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] crp-reset-ack player={} server={} success={} clientState={} inFlight={}",
                player.getUsername(), server == null ? "<none>" : server.getServerInfo().getName(),
                ((Context.ClientContext) msg.getContext()).success(), player.getConnection().getState(),
                player.getConnectionInFlight() == null ? "<none>" : player.getConnectionInFlight().getClass().getName());
        takePendingResetLoginPacket(player);
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
  public boolean forgeHandshakeFromOutPreBridge = false;
  public RegisteredServer resetReconnectTarget = null;
  public boolean keepConnectedServerDuringNextReset = false;

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
    nextPhase().forgeHandshakeFromOutPreBridge = this.forgeHandshakeFromOutPreBridge;
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
    COMPLETE.forgeHandshakeFromOutPreBridge = forgeHandshakeFromOutPreBridge;
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

  public void reconnectFreshAfterReset(RegisteredServer target) {
    WAITING_RESET.resetReconnectTarget = target;
    WAITING_RESET.keepConnectedServerDuringNextReset = true;
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
      if (connection.getChannel().pipeline().get(ForgeConstants.RESET_LISTENER) != null) {
        connection.getChannel().pipeline().remove(ForgeConstants.RESET_LISTENER);
        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] COMPLETE removed CRP raw guard player={} state={}",
                player.getUsername(), connection.getState());
      }
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
        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset start player={} state={} inFlight={} connected={} type={}",
                player.getUsername(), connection.getState(),
                player.getConnectionInFlight() == null ? "null" : player.getConnectionInFlight().getServerInfo().getName(),
                player.getConnectedServer() == null ? "null" : player.getConnectedServer().getServerInfo().getName(),
                connection.getType());

        boolean keepConnectedServer = WAITING_RESET.keepConnectedServerDuringNextReset;
        WAITING_RESET.keepConnectedServerDuringNextReset = false;
        VelocityServerConnection oldServer = player.getConnectedServer();

        if (oldServer != null && keepConnectedServer) {
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset preserving connected server during reset player={} connected={} connectedClass={} state={}",
                  player.getUsername(), oldServer.getServerInfo().getName(), oldServer.getClass().getName(), connection.getState());
          try {
            oldServer.ensureConnected().setAutoReading(false);
            Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset paused connected server autoread player={} connected={}",
                    player.getUsername(), oldServer.getServerInfo().getName());
          } catch (IllegalStateException e) {
            Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset kept connected server but could not pause autoread player={} connected={} reason={}",
                    player.getUsername(), oldServer.getServerInfo().getName(), e.toString());
          }
        } else if (oldServer != null) {
          Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] crp-mark-old-backend-transition player={} oldServer={}",
                  player.getUsername(), oldServer.getServerInfo().getName());
          oldServer.setConnectionPhase(BackendConnectionPhases.IN_TRANSITION);
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset disconnecting connected server player={} connected={} state={}",
                  player.getUsername(), oldServer.getServerInfo().getName(), connection.getState());
          oldServer.disconnect();
          player.setConnectedServer(null);
        }

        if (player.getConnectionInFlight() != null) {
          player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(false);
        }

        ChannelFuture resetFuture;
        if (connection.getState() == StateRegistry.PLAY || connection.getState() == StateRegistry.CONFIG) {
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset using PLAY plugin-message player={} state={}",
                  player.getUsername(), connection.getState());
          resetFuture = connection.write(new PluginMessagePacket("fml:handshake",
                  Unpooled.wrappedBuffer(ForgeHandshakeUtils.generatePluginResetPacket())));
          if (resetFuture != null) {
            resetFuture.addListener(future -> {
              if (future.isSuccess() && connection.getChannel().isActive()) {
                connection.setState(StateRegistry.LOGIN);
                Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset set LOGIN player={} state={}",
                        player.getUsername(), connection.getState());
              }
            });
          }
        } else {
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset using LOGIN login-wrapper player={} state={}",
                  player.getUsername(), connection.getState());
          resetFuture = connection.write(new LoginPluginMessagePacket(98, "fml:loginwrapper",
                  Unpooled.wrappedBuffer(ForgeHandshakeUtils.generateResetPacket())));
        }

        if (connection.getChannel().pipeline().get(ForgeConstants.RESET_LISTENER) == null) {
          connection.getChannel().pipeline().addBefore(Connections.MINECRAFT_DECODER,
                  ForgeConstants.RESET_LISTENER, new FML2CRPMResetCompleteDecoder());
        }
        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] CRP reset listener installed player={} state={} channel={}",
                player.getUsername(), connection.getState(), connection.getChannel());

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