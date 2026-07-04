package org.adde0109.ambassador.velocity.client;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.forge.packet.Context;
import org.adde0109.ambassador.forge.packet.GenericForgeLoginWrapperPacket;

public class FML2CRPMResetCompleteDecoder extends ChannelInboundHandlerAdapter {

  private boolean resetComplete;

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof ByteBuf buf) {
      boolean traceEnabled = Ambassador.getInstance().isTraceEnabled();
      if (!ctx.channel().isActive() || !buf.isReadable()) {
        if (traceEnabled) {
          Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] drop inactive/unreadable readable={} {}",
                  buf.readableBytes(), describe(ctx));
        }
        buf.release();
        return;
      }

      int originalReaderIndex = buf.readerIndex();
      if (traceEnabled) {
        Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] inbound readable={} preview={} {}",
                buf.readableBytes(), preview(buf, originalReaderIndex), describe(ctx));
      }

      int packetId;
      try {
        packetId = ProtocolUtils.readVarInt(buf);
      } catch (Exception e) {
        if (traceEnabled) {
          Ambassador.getInstance().traceWarn("[HZL-OUTPRE][CRP-RAW] failed read packetId preview={} {}",
                  preview(buf, originalReaderIndex), describe(ctx), e);
        }
        buf.release();
        return;
      }

      if (traceEnabled) {
        Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] packetId={} remaining={} resetComplete={} {}",
                packetId, buf.readableBytes(), resetComplete, describe(ctx));
      }

      if (!resetComplete) {
        if (packetId == 0x02 && buf.readableBytes() > 1) {
          try {
            int id = ProtocolUtils.readVarInt(buf);
            boolean success = buf.readBoolean();
            if (traceEnabled) {
              Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] waiting-reset login response responseId={} success={} remaining={} preview={} {}",
                      id, success, buf.readableBytes(), preview(buf, originalReaderIndex), describe(ctx));
            }
            if (id == 98) {
              resetComplete = true;
              try {
                if (traceEnabled) {
                  Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] reset ACK accepted responseId=98 success={} remaining={} {}",
                          success, buf.readableBytes(), describe(ctx));
                }
                ctx.fireChannelRead(GenericForgeLoginWrapperPacket.read(
                        Unpooled.EMPTY_BUFFER, Context.createClientContext(id, success, "fml:handshake")));
              } finally {
                buf.release();
              }
              return;
            }
          } catch (Exception e) {
            if (traceEnabled) {
              Ambassador.getInstance().traceWarn("[HZL-OUTPRE][CRP-RAW] waiting-reset login packet was not reset ACK preview={} {}",
                      preview(buf, originalReaderIndex), describe(ctx), e);
            }
          }
        }

        if (traceEnabled) {
          Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] waiting-reset drop packetId={} remaining={} preview={} {}",
                  packetId, buf.readableBytes(), preview(buf, originalReaderIndex), describe(ctx));
        }
        buf.release();
        return;
      }

      if (packetId == 0x02) {
        try {
          int responseId = ProtocolUtils.readVarInt(buf);
          boolean success = buf.readBoolean();
          int payloadBytes = buf.readableBytes();
          if (traceEnabled) {
            Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] post-reset login response responseId={} success={} payloadBytes={} preview={} {}",
                    responseId, success, payloadBytes, preview(buf, originalReaderIndex), describe(ctx));
          }
          if (responseId == 98 && success && payloadBytes <= 1) {
            if (traceEnabled) {
              Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] post-reset drop duplicate/late reset ACK responseId=98 payloadBytes={} {}",
                      payloadBytes, describe(ctx));
            }
            buf.release();
            return;
          }
        } catch (Exception e) {
          if (traceEnabled) {
            Ambassador.getInstance().traceWarn("[HZL-OUTPRE][CRP-RAW] post-reset failed to inspect login response preview={} {}",
                    preview(buf, originalReaderIndex), describe(ctx), e);
          }
        }
        buf.readerIndex(originalReaderIndex);
      } else {
        if (traceEnabled) {
          Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] post-reset drop stale non-login packetId={} remaining={} preview={} {}",
                  packetId, buf.readableBytes(), preview(buf, originalReaderIndex), describe(ctx));
        }
        buf.release();
        return;
      }
    }
    ctx.fireChannelRead(msg);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (Ambassador.getInstance().isTraceEnabled()) {
      Ambassador.getInstance().trace("[HZL-OUTPRE][CRP-RAW] channelInactive resetComplete={} {}",
              resetComplete, describe(ctx));
    }
    super.channelInactive(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (Ambassador.getInstance().isTraceEnabled()) {
      Ambassador.getInstance().traceWarn("[HZL-OUTPRE][CRP-RAW] exceptionCaught resetComplete={} {}",
              resetComplete, describe(ctx), cause);
    }
    super.exceptionCaught(ctx, cause);
  }

  private static String describe(ChannelHandlerContext ctx) {
    MinecraftConnection connection = ctx.pipeline().get(MinecraftConnection.class);
    if (connection == null) {
      return "connection=null channel=" + ctx.channel();
    }
    Object association = connection.getAssociation();
    String associationName = association == null ? "null" : association.getClass().getName();
    String playerName = association instanceof ConnectedPlayer player ? " player=" + player.getUsername() : "";
    return "state=" + connection.getState()
            + " protocol=" + connection.getProtocolVersion()
            + " assoc=" + associationName
            + playerName
            + " channel=" + ctx.channel();
  }

  private static String preview(ByteBuf buf, int readerIndex) {
    int length = Math.min(buf.writerIndex() - readerIndex, 24);
    StringBuilder builder = new StringBuilder(length * 3);
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        builder.append(' ');
      }
      int value = buf.getUnsignedByte(readerIndex + i);
      if (value < 16) {
        builder.append('0');
      }
      builder.append(Integer.toHexString(value));
    }
    return builder.toString();
  }
}