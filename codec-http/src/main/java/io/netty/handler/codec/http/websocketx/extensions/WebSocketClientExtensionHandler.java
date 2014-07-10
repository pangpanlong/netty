/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx.extensions;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.CodecException;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * This handler negotiates and initializes the WebSocket Extensions.
 *
 * This implementation negotiates the extension with the server in a defined order,
 * ensures that the successfully negotiated extensions are consistent between them,
 * and initializes the channel pipeline with the extension decoder and encoder.
 *
 * Find a basic implementation for compression extensions at
 * <tt>io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler</tt>.
 */
public class WebSocketClientExtensionHandler extends ChannelHandlerAdapter {

    private final List<WebSocketClientExtensionHandshaker> extensionHandshakers;

    /**
     * Constructor
     *
     * @param extensionHandshakers
     *      The extension handshaker in priority order. A handshaker could be repeated many times
     *      with fallback configuration.
     */
    public WebSocketClientExtensionHandler(WebSocketClientExtensionHandshaker... extensionHandshakers) {
        if (extensionHandshakers == null) {
            throw new NullPointerException("extensionHandshakers");
        }
        if (extensionHandshakers.length == 0) {
            throw new IllegalArgumentException("extensionHandshakers must contains at least one handshaker");
        }
        this.extensionHandshakers = Arrays.asList(extensionHandshakers);
    }

    @Override
    public void write(final ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpRequest && WebSocketExtensionUtil.isWebsocketUpgrade((HttpRequest) msg)) {
            HttpRequest request = (HttpRequest) msg;
            String headerValue = request.headers().get(HttpHeaders.Names.SEC_WEBSOCKET_EXTENSIONS);

            for (WebSocketClientExtensionHandshaker extentionHandshaker : extensionHandshakers) {
                WebSocketExtensionData extensionData = extentionHandshaker.newRequestData();
                headerValue = WebSocketExtensionUtil.appendExtension(headerValue,
                        extensionData.name(), extensionData.parameters());
            }

            request.headers().set(HttpHeaders.Names.SEC_WEBSOCKET_EXTENSIONS, headerValue);
        }

        super.write(ctx, msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;

            if (WebSocketExtensionUtil.isWebsocketUpgrade(response)) {
                String extensionsHeader = response.headers().get(HttpHeaders.Names.SEC_WEBSOCKET_EXTENSIONS);

                if (extensionsHeader != null) {
                    List<WebSocketExtensionData> extensions =
                            WebSocketExtensionUtil.extractExtensions(extensionsHeader);
                    List<WebSocketClientExtension> validExtensions =
                            new ArrayList<WebSocketClientExtension>(extensions.size());
                    int rsv = 0;

                    for (WebSocketExtensionData extensionData : extensions) {
                        Iterator<WebSocketClientExtensionHandshaker> extensionHandshakersIterator =
                                extensionHandshakers.iterator();
                        WebSocketClientExtension validExtension = null;

                        while (validExtension == null && extensionHandshakersIterator.hasNext()) {
                            WebSocketClientExtensionHandshaker extensionHandshaker =
                                    extensionHandshakersIterator.next();
                            validExtension = extensionHandshaker.handshakeExtension(extensionData);
                        }

                        if (validExtension != null && ((validExtension.rsv() & rsv) == 0)) {
                            rsv = rsv | validExtension.rsv();
                            validExtensions.add(validExtension);
                        } else {
                            throw new CodecException(
                                    "invalid WebSocket Extension handhshake for \"" + extensionsHeader + "\"");
                        }
                    }

                    for (WebSocketClientExtension validExtension : validExtensions) {
                        WebSocketExtensionDecoder decoder = validExtension.newExtensionDecoder();
                        WebSocketExtensionEncoder encoder = validExtension.newExtensionEncoder();
                        ctx.pipeline().addAfter(ctx.name(), decoder.getClass().getName(), decoder);
                        ctx.pipeline().addAfter(ctx.name(), encoder.getClass().getName(), encoder);
                    }
                }

                ctx.pipeline().remove(ctx.name());
            }
        }

        super.channelRead(ctx, msg);
    }

}