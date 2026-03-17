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
package de.gematik.test.tiger.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal HTTP/2 cleartext (h2c) client for testing. Sends the HTTP/2 connection preface directly
 * (prior-knowledge mode), unlike Java's HttpClient which uses HTTP/1.1 upgrade and falls back to
 * h1.
 */
@Slf4j
public class H2cTestClient implements Closeable {

  private final EventLoopGroup group =
      new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
  private Channel channel;
  private final CompletableFuture<FullHttpResponse> responseFuture = new CompletableFuture<>();

  public FullHttpResponse sendGet(String host, int port, String path) throws Exception {
    Bootstrap b = new Bootstrap();
    b.group(group)
        .channel(NioSocketChannel.class)
        .handler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                Http2Connection connection = new DefaultHttp2Connection(false);
                HttpToHttp2ConnectionHandler h2Handler =
                    new HttpToHttp2ConnectionHandlerBuilder()
                        .frameListener(
                            new DelegatingDecompressorFrameListener(
                                connection,
                                new InboundHttp2ToHttpAdapterBuilder(connection)
                                    .maxContentLength(1024 * 1024)
                                    .propagateSettings(true)
                                    .validateHttpHeaders(false)
                                    .build(),
                                0))
                        .connection(connection)
                        .flushPreface(true)
                        .frameLogger(
                            new Http2FrameLogger(LogLevel.INFO, H2cTestClient.class.getName()))
                        .build();
                ch.pipeline().addLast(h2Handler);
                ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                ch.pipeline().addLast(new ResponseHandler(responseFuture));
              }
            });

    channel = b.connect(host, port).sync().channel();

    // Wait for SETTINGS from server (the adapter fires a FullHttpRequest for SETTINGS)
    // Then send a GET request
    channel
        .eventLoop()
        .schedule(
            () -> {
              DefaultFullHttpRequest request =
                  new DefaultFullHttpRequest(
                      HttpVersion.HTTP_1_1, HttpMethod.GET, path, Unpooled.EMPTY_BUFFER);
              request.headers().set(HttpHeaderNames.HOST, host + ":" + port);
              request
                  .headers()
                  .set(
                      HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(),
                      HttpScheme.HTTP.name());
              request.headers().set(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), "3");
              channel.writeAndFlush(request);
            },
            200,
            TimeUnit.MILLISECONDS);

    return responseFuture.get(10, TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.close();
    }
    group.shutdownGracefully();
  }

  private static class ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private final CompletableFuture<FullHttpResponse> responseFuture;

    ResponseHandler(CompletableFuture<FullHttpResponse> responseFuture) {
      this.responseFuture = responseFuture;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
      // Ignore SETTINGS responses (status 0 or empty)
      if (msg.status().code() > 0) {
        responseFuture.complete(msg.retain());
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      responseFuture.completeExceptionally(cause);
      ctx.close();
    }
  }
}
