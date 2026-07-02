package org.adde0109.ambassador.velocity;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.ModInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.StateRegistry;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.forge.ForgeConstants;
import org.adde0109.ambassador.forge.ForgeFMLConnectionType;
import org.adde0109.ambassador.forge.VelocityForgeClientConnectionPhase;
import org.adde0109.ambassador.forge.pipeline.ForgeLoginWrapperCodec;
import org.adde0109.ambassador.forge.pipeline.ForgeLoginWrapperHandler;

public class VelocityEventHandler {

  private final Ambassador ambassador;

  public VelocityEventHandler(Ambassador ambassador) {
    this.ambassador = ambassador;
  }

  @Subscribe(order = PostOrder.FIRST)
  public void onPostLoginEvent(PostLoginEvent event, Continuation continuation) {
    ConnectedPlayer player = (ConnectedPlayer) event.getPlayer();
    if (player.getPhase() instanceof VelocityForgeClientConnectionPhase phase && !phase.consideredComplete()) {
      ((VelocityServer) Ambassador.getInstance().server).unregisterConnection(player);

      player.getConnection().eventLoop().submit(() -> {
        if (player.getConnection().getChannel().pipeline().get(ForgeConstants.FORGE_HANDSHAKE_DECODER) != null) {
          return;
        }
        player.getConnection().setState(StateRegistry.LOGIN);

        player.getConnection().getChannel().pipeline().addBefore(
                Connections.HANDLER,
                ForgeConstants.FORGE_HANDSHAKE_DECODER, new ForgeLoginWrapperCodec(
                        player.getConnection().getType() == ForgeConstants.ForgeFML3));
        player.getConnection().getChannel().pipeline().addAfter(
                ForgeConstants.FORGE_HANDSHAKE_DECODER,
                ForgeConstants.FORGE_HANDSHAKE_HANDLER, new ForgeLoginWrapperHandler(player));
      });
    }
    //event.getPlayer().sendMessage(Component.text("post login event"));
    continuation.resume();
  }

  @Subscribe(order = PostOrder.LAST)
  public void onPlayerChooseInitialServerEvent(PlayerChooseInitialServerEvent event, Continuation continuation) {
    ConnectedPlayer player = (ConnectedPlayer) event.getPlayer();
    if (!(player.getPhase() instanceof VelocityForgeClientConnectionPhase phase)) {
      continuation.resume();
      return;
    }
    RegisteredServer chosenServer = Ambassador.getTemporaryForced().remove(player.getUsername());
    if (chosenServer != null)
      event.setInitialServer(chosenServer);
    //event.getPlayer().sendMessage(Component.text("choose server event"));
    continuation.resume();
  }


  @Subscribe(order = PostOrder.FIRST)
  public void onServerPreConnectDebugFirst(ServerPreConnectEvent event) {
    logServerPreConnect("first", event);
  }

  @Subscribe(order = PostOrder.LAST)
  public void onServerPreConnectDebugLast(ServerPreConnectEvent event) {
    logServerPreConnect("last", event);
  }

  @Subscribe(order = PostOrder.FIRST)
  public void onKickedFromServerDebugFirst(KickedFromServerEvent event) {
    logKickedFromServer("first", event);
  }

  @Subscribe(order = PostOrder.LAST)
  public void onKickedFromServerDebugLast(KickedFromServerEvent event) {
    logKickedFromServer("last", event);
  }

  private void logServerPreConnect(String stage, ServerPreConnectEvent event) {
    if (!Ambassador.getInstance().config.isDebugMode()) {
      return;
    }
    ConnectedPlayer player = (ConnectedPlayer) event.getPlayer();
    String currentServer = player.getConnectedServer() == null
            ? "<none>"
            : player.getConnectedServer().getServerInfo().getName();
    String previousServer = event.getPreviousServer() == null
            ? "<none>"
            : event.getPreviousServer().getServerInfo().getName();
    String resultServer = event.getResult().getServer()
            .map(server -> server.getServerInfo().getName())
            .orElse("<denied>");
    String inFlight = player.getConnectionInFlight() == null
            ? "<none>"
            : player.getConnectionInFlight().getClass().getName();
    Ambassador.getInstance().debugInfo(
            "[AMB-HZL-DEBUG] server-pre-connect-{} player={} original={} result={} previous={} current={} inFlight={} clientState={} phase={}",
            stage, player.getUsername(), event.getOriginalServer().getServerInfo().getName(), resultServer,
            previousServer, currentServer, inFlight, player.getConnection().getState(), player.getPhase());
  }

  private void logKickedFromServer(String stage, KickedFromServerEvent event) {
    if (!Ambassador.getInstance().config.isDebugMode()) {
      return;
    }
    ConnectedPlayer player = (ConnectedPlayer) event.getPlayer();
    String currentServer = player.getConnectedServer() == null
            ? "<none>"
            : player.getConnectedServer().getServerInfo().getName();
    String inFlight = player.getConnectionInFlight() == null
            ? "<none>"
            : player.getConnectionInFlight().getClass().getName();
    Ambassador.getInstance().debugInfo(
            "[AMB-HZL-DEBUG] kicked-from-server-{} player={} kickedServer={} duringConnect={} reason={} result={} current={} inFlight={} clientState={} phase={}",
            stage, player.getUsername(), event.getServer().getServerInfo().getName(),
            event.kickedDuringServerConnect(), event.getServerKickReason().map(Object::toString).orElse("<none>"),
            describeKickResult(event.getResult()), currentServer, inFlight,
            player.getConnection().getState(), player.getPhase());
  }

  private String describeKickResult(KickedFromServerEvent.ServerKickResult result) {
    if (result instanceof KickedFromServerEvent.RedirectPlayer redirect) {
      return "redirect:" + redirect.getServer().getServerInfo().getName();
    }
    if (result instanceof KickedFromServerEvent.DisconnectPlayer disconnect) {
      return "disconnect:" + disconnect.getReasonComponent();
    }
    if (result instanceof KickedFromServerEvent.Notify notify) {
      return "notify:" + notify.getMessageComponent();
    }
    return result.getClass().getName();
  }
  @Subscribe
  public void onPlayerChannelRegisterEvent(PlayerChannelRegisterEvent event) {
    ConnectedPlayer player = (ConnectedPlayer) event.getPlayer();
    if (!(player.getConnection().getType() instanceof ForgeFMLConnectionType)) {
      return;
    }
    player.setModInfo(new ModInfo("Channels", event.getChannels().stream().map((id) -> {
      return new ModInfo.Mod(id.getId(), "");
    }).toList()));

    VelocityForgeClientConnectionPhase clientPhase = (VelocityForgeClientConnectionPhase) player.getPhase();
    //If reset typ is still unknown, set it!
    if (clientPhase.getResetType() == VelocityForgeClientConnectionPhase.ClientResetType.UNKNOWN) {
      clientPhase.updateResetType(player);
    }
  }

}
