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

import de.gematik.test.tiger.proxy.handler.BinaryExchangeHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for installing HTTP/2 codec pipelines. Centralizes the repeated h2 pipeline
 * construction used across {@code PortUnificationHandler}, {@code HttpConnectHandler}, and {@code
 * HttpClientInitializer}.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Http2PipelineHelper {

  // TODO: make max content length configurable instead of Integer.MAX_VALUE
  private static final int MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

  /**
   * Adds HTTP/2 codecs to the pipeline.
   *
   * @param pipeline the channel pipeline to add codecs to
   * @param isServer true for server-side (incoming connections), false for client-side (outgoing)
   * @param flushPreface true to send the HTTP/2 connection preface immediately (needed for client
   *     and server-side h2c)
   * @param logContext class name used for frame logging (only active at TRACE level)
   */
  public static void addHttp2Codecs(
      ChannelPipeline pipeline, boolean isServer, boolean flushPreface, Class<?> logContext) {
    addHttp2Codecs(pipeline, isServer, flushPreface, logContext, null);
  }

  /**
   * Adds HTTP/2 codecs to the pipeline, optionally with a raw byte tap for rbel frame logging.
   *
   * @param pipeline the channel pipeline to add codecs to
   * @param isServer true for server-side (incoming connections), false for client-side (outgoing)
   * @param flushPreface true to send the HTTP/2 connection preface immediately
   * @param logContext class name used for frame logging (only active at TRACE level)
   * @param binaryExchangeHandler if non-null, installs a tap handler before the h2 codec that
   *     copies raw bytes to rbel for structural frame parsing
   */
  public static void addHttp2Codecs(
      ChannelPipeline pipeline,
      boolean isServer,
      boolean flushPreface,
      Class<?> logContext,
      BinaryExchangeHandler binaryExchangeHandler) {
    if (binaryExchangeHandler != null) {
      pipeline.addLast(new Http2RawByteTapHandler(binaryExchangeHandler, isServer));
    }

    Http2Connection connection = new DefaultHttp2Connection(isServer);
    HttpToHttp2ConnectionHandlerBuilder handlerBuilder =
        new HttpToHttp2ConnectionHandlerBuilder()
            .frameListener(
                new DelegatingDecompressorFrameListener(
                    connection,
                    new InboundHttp2ToHttpAdapterBuilder(connection)
                        .maxContentLength(MAX_CONTENT_LENGTH)
                        .propagateSettings(true)
                        .validateHttpHeaders(false)
                        .build(),
                    0))
            .connection(connection)
            .flushPreface(flushPreface);

    if (log.isTraceEnabled()) {
      handlerBuilder.frameLogger(new Http2FrameLogger(LogLevel.TRACE, logContext.getName()));
    }

    pipeline.addLast(handlerBuilder.build());
  }
}
