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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;

/**
 * Configurable HTTP/2 test server. Supports cleartext (h2c) and TLS (h2) modes, with optional
 * GOAWAY after each response. Responds to all requests with a fixed JSON body and status 200.
 */
public class H2TestServer implements Closeable {

  @Getter private int port;
  private final boolean useTls;
  private final boolean sendGoaway;
  private final String responseBody;
  private final EventLoopGroup bossGroup =
      new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
  private final EventLoopGroup workerGroup =
      new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
  private Channel serverChannel;
  @Getter private final AtomicInteger requestsReceived = new AtomicInteger(0);

  private H2TestServer(int port, boolean useTls, boolean sendGoaway, String responseBody) {
    this.port = port;
    this.useTls = useTls;
    this.sendGoaway = sendGoaway;
    this.responseBody = responseBody;
  }

  /** Creates a cleartext h2c server. */
  public static H2TestServer h2c(int port) {
    return new H2TestServer(port, false, false, "{\"h2\":\"ok\"}");
  }

  /** Creates an h2 over TLS server with ALPN. */
  public static H2TestServer h2Tls(int port) {
    return new H2TestServer(port, true, false, "{\"h2\":\"ok\"}");
  }

  /** Creates a cleartext h2c server that sends GOAWAY after each response. */
  public static H2TestServer h2cWithGoaway(int port) {
    return new H2TestServer(port, false, true, "{\"goaway\":\"ok\"}");
  }

  public void start() throws Exception {
    SslContext sslCtx = useTls ? buildSslContext() : null;

    ServerBootstrap b = new ServerBootstrap();
    b.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                if (sslCtx != null) {
                  ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()));
                  ch.pipeline()
                      .addLast(
                          new ApplicationProtocolNegotiationHandler("") {
                            @Override
                            protected void configurePipeline(
                                ChannelHandlerContext ctx, String proto) {
                              if (!ApplicationProtocolNames.HTTP_2.equals(proto)) {
                                ctx.close();
                                return;
                              }
                              addH2Pipeline(ch.pipeline());
                            }
                          });
                } else {
                  addH2Pipeline(ch.pipeline());
                }
              }
            });
    serverChannel = b.bind(port).sync().channel();
    port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
  }

  private void addH2Pipeline(ChannelPipeline pipeline) {
    Http2Connection connection = new DefaultHttp2Connection(true);
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
            .frameLogger(new Http2FrameLogger(LogLevel.INFO, H2TestServer.class.getName()))
            .build();
    pipeline.addLast(h2Handler);
    pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
    pipeline.addLast(new H2ResponseHandler(requestsReceived, responseBody, sendGoaway, h2Handler));
  }

  @SuppressWarnings({"deprecation", "java:S1874"}) // No replacement in Netty 4.2
  private static SslContext buildSslContext() throws Exception {
    SelfSignedCertificate ssc = new SelfSignedCertificate();
    return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
        .protocols("TLSv1.2", "TLSv1.3")
        .applicationProtocolConfig(
            new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2))
        .build();
  }

  @Override
  public void close() {
    if (serverChannel != null) {
      serverChannel.close();
    }
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
  }

  private static class H2ResponseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final AtomicInteger requestsReceived;
    private final String responseBody;
    private final boolean sendGoaway;
    private final HttpToHttp2ConnectionHandler h2Handler;

    H2ResponseHandler(
        AtomicInteger requestsReceived,
        String responseBody,
        boolean sendGoaway,
        HttpToHttp2ConnectionHandler h2Handler) {
      this.requestsReceived = requestsReceived;
      this.responseBody = responseBody;
      this.sendGoaway = sendGoaway;
      this.h2Handler = h2Handler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
      requestsReceived.incrementAndGet();
      byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
      DefaultFullHttpResponse response =
          new DefaultFullHttpResponse(
              HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
      response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
      String streamId =
          request.headers().get(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
      if (streamId != null) {
        response.headers().set(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), streamId);
      }

      ChannelFuture writeFuture = ctx.writeAndFlush(response);

      if (sendGoaway) {
        writeFuture.addListener(
            future -> {
              Http2ConnectionEncoder encoder = h2Handler.encoder();
              int lastStreamId = streamId != null ? Integer.parseInt(streamId) : 0;
              encoder.writeGoAway(
                  ctx,
                  lastStreamId,
                  Http2Error.NO_ERROR.code(),
                  Unpooled.EMPTY_BUFFER,
                  ctx.newPromise());
              ctx.flush();
            });
      }
    }
  }
}
