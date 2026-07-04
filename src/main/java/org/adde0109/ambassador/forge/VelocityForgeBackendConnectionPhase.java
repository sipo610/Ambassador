package org.adde0109.ambassador.forge;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.BackendConnectionPhase;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.channel.ChannelFuture;
import net.kyori.adventure.text.Component;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.velocity.backend.ForgeLoginSessionHandler;
import org.adde0109.ambassador.forge.packet.*;
import org.adde0109.ambassador.forge.pipeline.CommandDecoderErrorCatcher;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public enum VelocityForgeBackendConnectionPhase implements BackendConnectionPhase {
  NOT_STARTED {
    @Override
    VelocityForgeBackendConnectionPhase nextPhase() {
      return IN_PROGRESS;
    }

    @Override
    public boolean consideredComplete() {
      //Safe if the server hasn't initiated the handshake yet.
      return true;
    }
  },
  IN_PROGRESS {
    @Override
    public void onLoginSuccess(VelocityServerConnection serverCon, ConnectedPlayer player) {
      serverCon.setConnectionPhase(VelocityForgeBackendConnectionPhase.COMPLETE);

      serverCon.getConnection().getChannel().pipeline().addBefore(Connections.MINECRAFT_DECODER,
              ForgeConstants.COMMAND_ERROR_CATCHER,
              new CommandDecoderErrorCatcher(serverCon.getConnection().getProtocolVersion(), player));
    }

    @Override
    void onTransitionToNewPhase(VelocityServerConnection connection) {
      MinecraftConnection mc = connection.getConnection();
      if (mc != null) {
        //This looks ugly. But unless the player didn't have a FML marker, we're fine.
        mc.setType(connection.getPlayer().getConnection().getType());
      }
    }
  },

  COMPLETE {
    @Override
    public boolean consideredComplete() {
      return true;
    }
  };

  public ForgeHandshake handshake = new ForgeHandshake();
  CountDownLatch remainingRegistries;

  VelocityForgeBackendConnectionPhase() {
  }

  public void handle(VelocityServerConnection server, ConnectedPlayer player, IForgeLoginWrapperPacket<Context> message) {
    VelocityForgeBackendConnectionPhase newPhase = getNewPhase(server, message);

    server.setConnectionPhase(newPhase);

    //Forge -> Forge
    VelocityForgeClientConnectionPhase clientPhase = (VelocityForgeClientConnectionPhase) player.getPhase();

    if (!player.isActive()) {
      Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend closing because frontend player is inactive server={} player={} msg={} backendState={}",
              server.getServerInfo().getName(), player.getUsername(), message.getClass().getSimpleName(), server.getConnection().getState());
      server.disconnect();
      return;
    }

    Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend handle server={} player={} msg={} clientPhase={} clientComplete={} clientState={} resetType={} backendPhase={} backendState={} connected={} inFlight={}",
            server.getServerInfo().getName(), player.getUsername(), message.getClass().getSimpleName(),
            player.getPhase().getClass().getName(), clientPhase.consideredComplete(),
            player.getConnection().getState(), clientPhase.getResetType(),
            server.getPhase(), server.getConnection().getState(),
            player.getConnectedServer() == null ? "null" : player.getConnectedServer().getServerInfo().getName(),
            player.getConnectionInFlight() == null ? "null" : player.getConnectionInFlight().getServerInfo().getName());

    boolean outPreBridge = isOutPreBridge(server);
    Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend classification server={} outPreBridge={} baselineFromOutPre={} clientComplete={}",
            server.getServerInfo().getName(), outPreBridge, clientPhase.forgeHandshakeFromOutPreBridge, clientPhase.consideredComplete());
    boolean replacingOutPreBaseline = clientPhase.consideredComplete()
            && clientPhase.forgeHandshakeFromOutPreBridge
            && !outPreBridge;

    if (clientPhase.consideredComplete()
            && clientPhase.getResetType() == VelocityForgeClientConnectionPhase.ClientResetType.UNKNOWN) {
      Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend resetType UNKNOWN, updating from modInfo player={} modInfo={}",
              player.getUsername(), player.getModInfo());
      clientPhase.updateResetType(player);
      Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend resetType updated player={} resetType={}",
              player.getUsername(), clientPhase.getResetType());
    }

    if (!clientPhase.consideredComplete()) {
      //Initial Forge
      if (message instanceof ModListPacket modListPacket) {
        clientPhase.forgeHandshake = new ForgeHandshake();
        clientPhase.forgeHandshakeFromOutPreBridge = outPreBridge;
        Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] initial-forge-modlist player={} server={} serverMods={} serverRegistries={} packet={} outPreBridge={}",
                player.getUsername(), server.getServerInfo().getName(), modListPacket.getMods().size(),
                modListPacket.getRegistries().size(), message.getClass().getSimpleName(), outPreBridge);
      }
      if (message instanceof RegistryPacket registryPacket) {
        clientPhase.forgeHandshake.addRegistry(registryPacket);
      }
      forwardForgeLoginPacketToClient(player, message);
    } else {
      //Reset client if not ready to receive new handshake
      VelocityForgeClientConnectionPhase.ClientResetType resetType = clientPhase.getResetType();
      if (resetType == VelocityForgeClientConnectionPhase.ClientResetType.CRP
              || resetType == VelocityForgeClientConnectionPhase.ClientResetType.SR) {
        boolean reconnectFreshAfterOutPre = resetType == VelocityForgeClientConnectionPhase.ClientResetType.CRP
                && clientPhase.forgeHandshakeFromOutPreBridge
                && !outPreBridge;
        Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] reset-before-new-handshake player={} target={} resetType={} packet={} clientState={} connectedServer={} inFlight={} freshReconnectAfterOutPre={}",
                player.getUsername(), server.getServerInfo().getName(), resetType, message.getClass().getSimpleName(),
                player.getConnection().getState(),
                player.getConnectedServer() == null ? "<none>" : player.getConnectedServer().getServerInfo().getName(),
                player.getConnectionInFlight() == null ? "<none>" : player.getConnectionInFlight().getClass().getName(),
                reconnectFreshAfterOutPre);
        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend triggering client reset player={} resetType={} server={} firstMsg={} freshReconnectAfterOutPre={}",
                player.getUsername(), resetType, server.getServerInfo().getName(), message.getClass().getSimpleName(), reconnectFreshAfterOutPre);
        if (reconnectFreshAfterOutPre) {
          clientPhase.reconnectFreshAfterReset(server.getServer());
          clientPhase.resetConnectionPhase(player);
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend reset will silent-close stale probe and reconnect fresh after CRP player={} target={} clientState={} active={} inFlight={}",
                  player.getUsername(), server.getServerInfo().getName(), player.getConnection().getState(), player.isActive(),
                  player.getConnectionInFlight() == null ? "null" : player.getConnectionInFlight().getServerInfo().getName());
          if (server.getConnection().getActiveSessionHandler() instanceof ForgeLoginSessionHandler forgeLoginSessionHandler) {
            forgeLoginSessionHandler.suppressDisconnectHandling();
            Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend marked stale probe disconnect suppressed player={} target={}",
                    player.getUsername(), server.getServerInfo().getName());
          } else if (Ambassador.getInstance().isTraceEnabled()) {
            Object activeHandler = server.getConnection().getActiveSessionHandler();
            Ambassador.getInstance().traceWarn("[HZL-OUTPRE][TRACE] backend stale probe active handler was not ForgeLoginSessionHandler player={} target={} handler={}",
                    player.getUsername(), server.getServerInfo().getName(), activeHandler == null ? "null" : activeHandler.getClass().getName());
          }
          player.resetInFlightConnection();
          server.disconnect();
          Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend silent-closed stale probe after scheduling fresh reconnect player={} target={} clientState={} active={} inFlight={}",
                  player.getUsername(), server.getServerInfo().getName(), player.getConnection().getState(), player.isActive(),
                  player.getConnectionInFlight() == null ? "null" : player.getConnectionInFlight().getServerInfo().getName());
          return;
        }
        clientPhase.resetConnectionPhase(player);
        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend forwarding first post-reset msg player={} msg={} clientState={}",
                player.getUsername(), message.getClass().getSimpleName(), player.getConnection().getState());
        forwardForgeLoginPacketToClient(player, message);
        return;
      }

      if (clientPhase.forgeHandshake.getModListReplyPacket() == null) {
        //We have nothing to respond with during this handshake. Unable to proceed.
        if (Ambassador.getInstance().config.isEnableKickReset()) {
          //Kick-reset
          Ambassador.getInstance().reconnectSwitchPlayer(player);
        } else {
          Ambassador.getInstance().logger.error("Unable for {} to switch servers. Vanilla({}) -> Forge({}) switch " +
                          "without client side mod or kick-reset enabled is not yet supported!",
                  player.getGameProfile().getName(), player.getConnectedServer().getServerInfo().getName(),
                  server.getServerInfo().getName());
          server.disconnect();
        }
        return;
      }

      if (message instanceof ModListPacket modListPacket) {
        handshake = new ForgeHandshake();
        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend ModListPacket server={} registries={} replacingOutPreBaseline={} compatibleBefore={}",
                server.getServerInfo().getName(), modListPacket.getRegistries().size(), replacingOutPreBaseline,
                clientPhase.forgeHandshake.isCompatible(handshake));
        remainingRegistries = new CountDownLatch(modListPacket.getRegistries().size());

        Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] target-forge-modlist player={} target={} serverMods={} serverRegistries={} clientMods={} clientReplyRegistries={} clientStoredRegistries={} bypassMods={} bypassRegistries={} ignoredServerMods={}",
                player.getUsername(), server.getServer().getServerInfo().getName(), modListPacket.getMods().size(),
                modListPacket.getRegistries().size(), clientPhase.forgeHandshake.getModListReplyPacket().getMods().size(),
                clientPhase.forgeHandshake.getModListReplyPacket().getRegistries().size(),
                clientPhase.forgeHandshake.getRegistries().size(),
                Ambassador.getInstance().config.isBypassModCheck(), Ambassador.getInstance().config.isBypassRegistryCheck(),
                Ambassador.getInstance().config.getIgnoredServerMods());

        if (Ambassador.getInstance().config.isDebugMode())
          player.sendMessage(Component.text("Expecting " + modListPacket.getRegistries().size() +
                  " packets from server " + server.getServer().getServerInfo().getName()));

        long time = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
          try {
            remainingRegistries.await();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }).thenAcceptAsync((v) -> {

          if (Ambassador.getInstance().config.isDebugMode()) {
            player.sendMessage(Component.text("Handshake took: " + (System.currentTimeMillis() - time) + " ms"));
            player.sendMessage(Component.text("Avg packet time" +
                    (System.currentTimeMillis() - time) / modListPacket.getRegistries().size() + " ms"));
          }

          List<String> ignoredServerMods = Ambassador.getInstance().config.getIgnoredServerMods();
          List<String> missingMods = missingRequiredMods(modListPacket, clientPhase.forgeHandshake.getModListReplyPacket(), ignoredServerMods);
          boolean registryCompatible = clientPhase.forgeHandshake.isCompatible(handshake);
          String registryDiff = describeRegistryDiff(clientPhase.forgeHandshake, handshake);
          Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] target-forge-registry-result player={} target={} elapsedMs={} missingMods={} ignoredServerMods={} registryCompatible={} replacingOutPreBaseline={} clientRegistries={} targetRegistries={} registryDiff={}",
                  player.getUsername(), server.getServer().getServerInfo().getName(), System.currentTimeMillis() - time,
                  missingMods.size(), ignoredServerMods, registryCompatible, replacingOutPreBaseline,
                  clientPhase.forgeHandshake.getRegistries().size(), handshake.getRegistries().size(), registryDiff);
          if (!missingMods.isEmpty() && !Ambassador.getInstance().config.isBypassModCheck()) {
            String shownMissingMods = describeMissingMods(missingMods);
            player.sendMessage(Component.text("缺少服务端要求的 Forge mods: " + shownMissingMods));
            Ambassador.getInstance().logger.error("Unable to switch because {} is missing required Forge mods for {}: {}",
                    player.getGameProfile().getName(), server.getServer().getServerInfo().getName(), shownMissingMods);
            server.disconnect();
            return;
          } else if (!missingMods.isEmpty()) {
            Ambassador.getInstance().debugWarn("[AMB-HZL-DEBUG] mod-check-bypassed player={} target={} missingMods={}",
                    player.getUsername(), server.getServer().getServerInfo().getName(), describeMissingMods(missingMods));
          }

          if (replacingOutPreBaseline && !registryCompatible) {
            Ambassador.getInstance().logger.error("Unable to switch from OutPre auth server {} to {} without client reset because registries differ for player {}: {}",
                    player.getConnectedServer() == null ? "<none>" : player.getConnectedServer().getServer().getServerInfo().getName(),
                    server.getServer().getServerInfo().getName(), player.getUsername(), registryDiff);
            server.disconnect();
          } else if (Ambassador.getInstance().config.isBypassRegistryCheck() || registryCompatible) {
            clientPhase.forgeHandshakeFromOutPreBridge = false;
            Ambassador.getInstance().debugInfo("[AMB-HZL-DEBUG] forwarding-client-modlist-to-backend player={} target={} clientMods={} clientChannels={}",
                    player.getUsername(), server.getServer().getServerInfo().getName(),
                    clientPhase.forgeHandshake.getModListReplyPacket().getMods().size(),
                    clientPhase.forgeHandshake.getModListReplyPacket().getChannels().size());
            server.ensureConnected().write(clientPhase.forgeHandshake.getModListReplyPacket());
          } else if (Ambassador.getInstance().config.isEnableKickReset()) {
            //Kick-reset
            Ambassador.getInstance().reconnectSwitchPlayer(player);
          } else {
            Ambassador.getInstance().logger.error("Unable to switch due to the registries of " +
                    server.getServer().getServerInfo().getName() + " being different from the registries of " +
                    player.getConnectedServer().getServer().getServerInfo().getName() + ": " + registryDiff);
            server.disconnect();
          }
        }, server.ensureConnected().eventLoop());
      } else if (message instanceof RegistryPacket registryPacket) {
        server.getConnection().write(new ACKPacket(Context.fromContext(message.getContext(), true)));
        handshake.addRegistry(registryPacket);
        remainingRegistries.countDown();
        Ambassador.getInstance().trace("[HZL-OUTPRE][TRACE] backend RegistryPacket server={} registry={} remaining={}",
                server.getServerInfo().getName(), registryPacket.getRegistryName(), remainingRegistries.getCount());
      } else if (message instanceof ConfigDataPacket) {
        server.getConnection().write(new ACKPacket(Context.fromContext(message.getContext(), true)));
      } else if (message instanceof GenericForgeLoginWrapperPacket<Context> packet
              && ForgeHandshakeUtils.ThirdPartyRegistryUtils.isThirdPartyPacket(packet)) {
        server.getConnection().write(
                ForgeHandshakeUtils.ThirdPartyRegistryUtils.getThirdPartyChannel(packet).
                        generateResponsePacket(
                                Context.ClientContext.fromContext(packet.getContext(), true),
                                clientPhase.forgeHandshake));
      }
    }
    //Forge server
    //To avoid unnecessary resets, we wait until we get the handshake even if we know that we should
    //reset because that the previous server was Forge.
  }

  static void forwardForgeLoginPacketToClient(ConnectedPlayer player, IForgeLoginWrapperPacket<?> message) {
    player.getConnection().setState(StateRegistry.LOGIN);
    ChannelFuture writeFuture = player.getConnection().write(message);
    if (writeFuture != null) {
      writeFuture.addListener(future -> {
        if (!future.isSuccess()) {
          Ambassador.getInstance().logger.warn("[HZL-OUTPRE] failed forwarding backend forge packet {} to client player={} clientState={}",
                  message.getClass().getSimpleName(), player.getUsername(), player.getConnection().getState(), future.cause());
        }
      });
    }
  }

  private static List<String> missingRequiredMods(ModListPacket serverModList, ModListReplyPacket clientModList,
                                                  List<String> ignoredServerMods) {
    Set<String> ignoredMods = new HashSet<>();
    for (String mod : ignoredServerMods) {
      ignoredMods.add(mod.toLowerCase(Locale.ROOT));
    }

    Set<String> clientMods = new HashSet<>();
    for (String mod : clientModList.getMods()) {
      clientMods.add(mod.toLowerCase(Locale.ROOT));
    }

    List<String> missing = new ArrayList<>();
    for (String mod : serverModList.getMods()) {
      String normalized = mod.toLowerCase(Locale.ROOT);
      if (!ignoredMods.contains(normalized) && !clientMods.contains(normalized)) {
        missing.add(mod);
      }
    }
    return missing;
  }

  private static String describeMissingMods(List<String> missingMods) {
    String shown = missingMods.stream().limit(20).collect(Collectors.joining(", "));
    if (missingMods.size() > 20) {
      shown += " ... +" + (missingMods.size() - 20);
    }
    return shown;
  }

  private static String describeRegistryDiff(ForgeHandshake clientHandshake, ForgeHandshake serverHandshake) {
    Map<String, Long> client = clientHandshake.getRegistries();
    Map<String, Long> server = serverHandshake.getRegistries();
    List<String> missing = new ArrayList<>();
    List<String> extra = new ArrayList<>();
    List<String> different = new ArrayList<>();

    for (Map.Entry<String, Long> entry : server.entrySet()) {
      Long clientValue = client.get(entry.getKey());
      if (clientValue == null) {
        missing.add(entry.getKey());
      } else if (!Objects.equals(clientValue, entry.getValue())) {
        different.add(entry.getKey());
      }
    }
    for (String key : client.keySet()) {
      if (!server.containsKey(key)) {
        extra.add(key);
      }
    }

    return "missing=" + describeRegistryNames(missing)
            + "; different=" + describeRegistryNames(different)
            + "; extra=" + describeRegistryNames(extra);
  }

  private static String describeRegistryNames(List<String> names) {
    if (names.isEmpty()) {
      return "0";
    }
    String shown = names.stream().limit(10).collect(Collectors.joining(", "));
    if (names.size() > 10) {
      shown += " ... +" + (names.size() - 10);
    }
    return names.size() + "[" + shown + "]";
  }

  public void onLoginSuccess(VelocityServerConnection serverCon, ConnectedPlayer player) {
  }

  private static boolean isOutPreBridge(VelocityServerConnection server) {
    if (server == null || server.getConnection() == null || server.getConnection().getActiveSessionHandler() == null) {
      return false;
    }
    Object handler = server.getConnection().getActiveSessionHandler();
    if (handler instanceof ForgeLoginSessionHandler forgeLoginSessionHandler) {
      handler = forgeLoginSessionHandler.getOriginal();
    }
    return handler.getClass().getName().startsWith("icu.h2l.login.vServer.outpre.");
  }

  void onTransitionToNewPhase(VelocityServerConnection connection) {
  }

  VelocityForgeBackendConnectionPhase nextPhase() {
    return this;
  }

  private VelocityForgeBackendConnectionPhase getNewPhase(VelocityServerConnection serverConnection,
                                                          IForgeLoginWrapperPacket<Context> packet) {
    VelocityForgeBackendConnectionPhase phaseToTransitionTo = nextPhase();
    if (phaseToTransitionTo != this) {
      phaseToTransitionTo.onTransitionToNewPhase(serverConnection);
    }
    return phaseToTransitionTo;
  }

  @Override
  public boolean handle(VelocityServerConnection server, ConnectedPlayer player, PluginMessagePacket message) {
    if (message.getChannel().equals("ambassador:commands")) {
      AvailableCommandsPacket packet = new AvailableCommandsPacket();
      packet.decode(message.content(), ProtocolUtils.Direction.CLIENTBOUND, server.getConnection().getProtocolVersion());
      server.getConnection().getActiveSessionHandler().handle(packet);
      return true;
    }
    return false;
  }

  public boolean consideredComplete() {
    return false;
  }
}