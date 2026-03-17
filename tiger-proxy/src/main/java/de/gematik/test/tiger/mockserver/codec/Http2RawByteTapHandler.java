/*
 *
 * Copyright 2021-2025 gematik GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.test.tiger.mockserver.codec;

import de.gematik.rbellogger.data.RbelMessageKind;
import de.gematik.rbellogger.util.RbelSocketAddress;
import de.gematik.test.tiger.mockserver.model.BinaryMessage;
import de.gematik.test.tiger.proxy.handler.BinaryExchangeHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.InetSocketAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A Netty handler that copies raw bytes flowing through the pipeline and feeds them to the
 * BinaryExchangeHandler for rbel logging. Installed before the HTTP/2 codec to capture raw h2
 * frames.
 *
 * <p>The handler does NOT consume the message — it passes it through unchanged to the next handler.
 */
@Slf4j
@RequiredArgsConstructor
public class Http2RawByteTapHandler extends ChannelInboundHandlerAdapter {

  private final BinaryExchangeHandler binaryExchangeHandler;
  private final boolean isServerSide;

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof ByteBuf byteBuf) {
      byte[] copy =
          ByteBufUtil.getBytes(byteBuf, byteBuf.readerIndex(), byteBuf.readableBytes(), false);
      recordMessage(ctx, copy);
    }
    // pass through unchanged
    super.channelRead(ctx, msg);
  }

  private void recordMessage(ChannelHandlerContext ctx, byte[] data) {
    try {
      InetSocketAddress localAddr = (InetSocketAddress) ctx.channel().localAddress();
      InetSocketAddress remoteAddr = (InetSocketAddress) ctx.channel().remoteAddress();

      RbelSocketAddress serverAddress;
      RbelSocketAddress clientAddress;
      RbelMessageKind messageKind;

      if (isServerSide) {
        // server side: remote is the client, local is the server
        clientAddress = RbelSocketAddress.create(remoteAddr);
        serverAddress = RbelSocketAddress.create(localAddr);
        messageKind = RbelMessageKind.REQUEST;
      } else {
        // client side: remote is the server, local is the client
        clientAddress = RbelSocketAddress.create(localAddr);
        serverAddress = RbelSocketAddress.create(remoteAddr);
        messageKind = RbelMessageKind.RESPONSE;
      }

      binaryExchangeHandler.onProxy(
          BinaryMessage.bytes(data), serverAddress, clientAddress, messageKind);
    } catch (Exception e) {
      log.debug("Error while tapping h2 raw bytes for rbel logging", e);
    }
  }
}
