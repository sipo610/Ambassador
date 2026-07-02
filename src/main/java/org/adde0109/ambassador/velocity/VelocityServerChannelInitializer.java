package org.adde0109.ambassador.velocity;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.HandshakeSessionHandler;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.network.ServerChannelInitializer;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import org.adde0109.ambassador.velocity.client.ClientConnectionDebugHandler;
import org.adde0109.ambassador.velocity.client.VelocityHandshakeSessionHandler;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

public class VelocityServerChannelInitializer extends ServerChannelInitializer {
  private Method INIT_CHANNEL;

  private final ChannelInitializer<?> delegate;
  private final VelocityServer server;

  public VelocityServerChannelInitializer(ChannelInitializer<?> delegate,VelocityServer server) {
    super(server);
    this.delegate = delegate;
    this.server = server;
    Method method = findInitChannel(delegate.getClass());
    method.setAccessible(true);
    this.INIT_CHANNEL = method;
  }

  private static Method findInitChannel(Class<?> cls) {
    Class<?> current = cls;
    while (current != null) {
      try {
        return current.getDeclaredMethod("initChannel", Channel.class);
      } catch (NoSuchMethodException ignored) {
        current = current.getSuperclass();
      }
    }
    throw new RuntimeException("initChannel(Channel) not found in hierarchy of " + cls.getName());
  }

  @Override
  protected void initChannel(@NotNull Channel ch){
    try {
      INIT_CHANNEL.invoke(delegate,ch);
    } catch (ReflectiveOperationException e) {
     throw new RuntimeException(e);
    }
    finally {
      if (ch.pipeline().get(MinecraftConnection.class) == null)
        super.initChannel(ch);
      if (ch.pipeline().get("ambassador_client_debug") == null && ch.pipeline().get(Connections.HANDLER) != null) {
        ch.pipeline().addBefore(Connections.HANDLER, "ambassador_client_debug", new ClientConnectionDebugHandler());
      }
      MinecraftConnection handler = ch.pipeline().get(MinecraftConnection.class);
      HandshakeSessionHandler originalSessionHandler = (HandshakeSessionHandler) handler.getActiveSessionHandler();
      handler.setActiveSessionHandler(StateRegistry.HANDSHAKE, new VelocityHandshakeSessionHandler(originalSessionHandler, handler, server));
    }
  }
}
