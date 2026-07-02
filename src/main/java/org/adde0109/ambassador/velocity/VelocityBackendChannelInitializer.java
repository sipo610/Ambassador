package org.adde0109.ambassador.velocity;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.network.BackendChannelInitializer;
import com.velocitypowered.proxy.network.Connections;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.adde0109.ambassador.forge.ForgeConstants;
import org.adde0109.ambassador.velocity.backend.BackendPluginMessageDebugHandler;
import org.adde0109.ambassador.velocity.backend.FMLMarkerAdder;
import org.adde0109.ambassador.velocity.backend.VelocityForgeBackendHandshakeHandler;
import org.slf4j.Logger;


import java.lang.reflect.Method;

public class VelocityBackendChannelInitializer extends BackendChannelInitializer {

  private final Method INIT_CHANNEL;

  private final ChannelInitializer<Channel> delegate;
  private final VelocityServer server;

  public VelocityBackendChannelInitializer(ChannelInitializer<Channel> delegate, VelocityServer server) {
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
  protected void initChannel(Channel ch) {
    try {
      INIT_CHANNEL.invoke(delegate, ch);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
    if (ch.pipeline().get(Connections.HANDLER) != null) {
      ch.pipeline().addBefore(Connections.HANDLER, "ambassador_backend_plugin_debug",
              new BackendPluginMessageDebugHandler());
    }
    ch.pipeline().addLast(ForgeConstants.MARKER_ADDER, new FMLMarkerAdder(server));
    ch.pipeline().addLast(ForgeConstants.HANDLER, new VelocityForgeBackendHandshakeHandler(server));
  }
}
