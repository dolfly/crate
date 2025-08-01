/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.http.netty4;

import static org.elasticsearch.env.Environment.PATH_HOME_SETTING;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_CREDENTIALS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_HEADERS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_METHODS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_ORIGIN;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ENABLED;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_MAX_AGE;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_BIND_HOST;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_COMPRESSION;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_COMPRESSION_LEVEL;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_DETAILED_ERRORS_ENABLED;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_MAX_CHUNK_SIZE;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_MAX_CONTENT_LENGTH;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_MAX_HEADER_SIZE;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_MAX_INITIAL_LINE_LENGTH;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_PORT;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_PUBLISH_HOST;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_PUBLISH_PORT;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_READ_TIMEOUT;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_RESET_COOKIES;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_TCP_KEEP_ALIVE;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_TCP_NO_DELAY;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_TCP_RECEIVE_BUFFER_SIZE;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_TCP_REUSE_ADDRESS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_TCP_SEND_BUFFER_SIZE;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_PIPELINING_MAX_EVENTS;
import static org.elasticsearch.http.netty4.cors.Netty4CorsHandler.ANY_ORIGIN;
import static org.elasticsearch.node.Node.NODE_NAME_SETTING;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.PortsRange;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.http.BindHttpException;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.http.netty4.cors.Netty4CorsConfig;
import org.elasticsearch.http.netty4.cors.Netty4CorsConfigBuilder;
import org.elasticsearch.http.netty4.cors.Netty4CorsHandler;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.rest.RestUtils;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.BindTransportException;
import org.elasticsearch.transport.StatsTracker;
import org.elasticsearch.transport.netty4.Netty4InboundStatsHandler;
import org.elasticsearch.transport.netty4.Netty4OutboundStatsHandler;
import org.elasticsearch.transport.netty4.Netty4Utils;
import org.jetbrains.annotations.Nullable;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;

import io.crate.auth.Authentication;
import io.crate.auth.HttpAuthUpstreamHandler;
import io.crate.auth.Protocol;
import io.crate.blob.BlobService;
import io.crate.common.exceptions.Exceptions;
import io.crate.netty.NettyBootstrap;
import io.crate.protocols.ConnectionStats;
import io.crate.protocols.http.HttpBlobHandler;
import io.crate.protocols.http.MainAndStaticFileHandler;
import io.crate.protocols.ssl.SslContextProvider;
import io.crate.protocols.ssl.SslSettings;
import io.crate.rest.action.SqlHttpHandler;
import io.crate.role.Roles;
import io.crate.session.Sessions;
import io.crate.types.DataTypes;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.Future;

public class Netty4HttpServerTransport extends AbstractLifecycleComponent implements HttpServerTransport {

    /*
     * Size in bytes of an individual message received by io.netty.handler.codec.MessageAggregator which accumulates the content for an
     * HTTP request. This number is used for estimating the maximum number of allowed buffers before the MessageAggregator's internal
     * collection of buffers is resized.
     *
     * By default we assume the Ethernet MTU (1500 bytes) but users can override it with a system property.
     */
    private static final ByteSizeValue MTU = new ByteSizeValue(Long.parseLong(System.getProperty("es.net.mtu", "1500")));

    private static final String SETTING_KEY_HTTP_NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS = "http.netty.max_composite_buffer_components";

    public static Setting<Integer> SETTING_HTTP_NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS =
        new Setting<>(SETTING_KEY_HTTP_NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS, (s) -> {
            ByteSizeValue maxContentLength = SETTING_HTTP_MAX_CONTENT_LENGTH.get(s);
            /*
             * Netty accumulates buffers containing data from all incoming network packets that make up one HTTP request in an instance of
             * io.netty.buffer.CompositeByteBuf (think of it as a buffer of buffers). Once its capacity is reached, the buffer will iterate
             * over its individual entries and put them into larger buffers (see io.netty.buffer.CompositeByteBuf#consolidateIfNeeded()
             * for implementation details). We want to to resize that buffer because this leads to additional garbage on the heap and also
             * increases the application's native memory footprint (as direct byte buffers hold their contents off-heap).
             *
             * With this setting we control the CompositeByteBuf's capacity (which is by default 1024, see
             * io.netty.handler.codec.MessageAggregator#DEFAULT_MAX_COMPOSITEBUFFER_COMPONENTS). To determine a proper default capacity for
             * that buffer, we need to consider that the upper bound for the size of HTTP requests is determined by `maxContentLength`. The
             * number of buffers that are needed depend on how often Netty reads network packets which depends on the network type (MTU).
             * We assume here that Elasticsearch receives HTTP requests via an Ethernet connection which has a MTU of 1500 bytes.
             *
             * Note that we are *not* pre-allocating any memory based on this setting but rather determine the CompositeByteBuf's capacity.
             * The tradeoff is between less (but larger) buffers that are contained in the CompositeByteBuf and more (but smaller) buffers.
             * With the default max content length of 100MB and a MTU of 1500 bytes we would allow 69905 entries.
             */
            long maxBufferComponentsEstimate = Math.round((double) (maxContentLength.getBytes() / MTU.getBytes()));
            // clamp value to the allowed range
            long maxBufferComponents = Math.max(2, Math.min(maxBufferComponentsEstimate, Integer.MAX_VALUE));
            return String.valueOf(maxBufferComponents);
            // Netty's CompositeByteBuf implementation does not allow less than two components.
        }, s -> Setting.parseInt(s, 2, Integer.MAX_VALUE, SETTING_KEY_HTTP_NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS), DataTypes.STRING, Property.NodeScope);

    public static final Setting<Integer> SETTING_HTTP_WORKER_COUNT = new Setting<>("http.netty.worker_count",
        (s) -> Integer.toString(EsExecutors.numberOfProcessors(s)),
        (s) -> Setting.parseInt(s, 1, "http.netty.worker_count"), DataTypes.INTEGER, Property.NodeScope, Property.Deprecated);

    public static final Setting<ByteSizeValue> SETTING_HTTP_NETTY_RECEIVE_PREDICTOR_SIZE =
        Setting.byteSizeSetting("http.netty.receive_predictor_size", new ByteSizeValue(64, ByteSizeUnit.KB), Property.NodeScope);

    private final Logger logger = LogManager.getLogger(getClass());

    protected final NetworkService networkService;
    protected final BigArrays bigArrays;

    protected final ByteSizeValue maxContentLength;
    protected final ByteSizeValue maxInitialLineLength;
    protected final ByteSizeValue maxHeaderSize;
    protected final ByteSizeValue maxChunkSize;

    protected final int pipeliningMaxEvents;

    protected final boolean compression;

    protected final int compressionLevel;

    protected final boolean resetCookies;

    protected final PortsRange port;

    protected final String[] bindHosts;

    protected final String[] publishHosts;

    protected final boolean detailedErrorsEnabled;
    protected final ThreadPool threadPool;
    /**
     * The registry used to construct parsers so they support {@link XContentParser#namedObject(Class, String, Object)}.
     */
    protected final NamedXContentRegistry xContentRegistry;

    protected final RecvByteBufAllocator recvByteBufAllocator;
    private final int readTimeoutMillis;

    protected final int maxCompositeBufferComponents;
    protected final Settings settings;

    protected volatile ServerBootstrap serverBootstrap;

    protected volatile BoundTransportAddress boundAddress;

    protected final List<Channel> serverChannels = new ArrayList<>();

    private final StatsTracker statsTracker = new StatsTracker();
    @Nullable
    private Netty4InboundStatsHandler inboundStatsHandler;
    @Nullable
    private Netty4OutboundStatsHandler outboundStatsHandler;

    private final Netty4CorsConfig corsConfig;
    private final NodeClient nodeClient;

    private final SslContextProvider sslContextProvider;

    private final BlobService blobService;
    private final Sessions sessions;
    private final Authentication authentication;
    private final Roles roles;
    private final CircuitBreakerService breakerService;

    private EventLoopGroup eventLoopGroup;


    public Netty4HttpServerTransport(Settings settings,
                                     NetworkService networkService,
                                     BigArrays bigArrays,
                                     ThreadPool threadPool,
                                     NamedXContentRegistry xContentRegistry,
                                     SslContextProvider sslContextProvider,
                                     BlobService blobService,
                                     Sessions sessions,
                                     Authentication authentication,
                                     Roles roles,
                                     CircuitBreakerService breakerService,
                                     NodeClient nodeClient) {
        this.settings = settings;
        this.networkService = networkService;
        this.bigArrays = bigArrays;
        this.threadPool = threadPool;
        this.xContentRegistry = xContentRegistry;
        this.sslContextProvider = sslContextProvider;
        this.blobService = blobService;
        this.sessions = sessions;
        this.authentication = authentication;
        this.roles = roles;
        this.breakerService = breakerService;
        this.nodeClient = nodeClient;

        this.maxContentLength = SETTING_HTTP_MAX_CONTENT_LENGTH.get(settings);
        this.maxChunkSize = SETTING_HTTP_MAX_CHUNK_SIZE.get(settings);
        this.maxHeaderSize = SETTING_HTTP_MAX_HEADER_SIZE.get(settings);
        this.maxInitialLineLength = SETTING_HTTP_MAX_INITIAL_LINE_LENGTH.get(settings);
        this.resetCookies = SETTING_HTTP_RESET_COOKIES.get(settings);
        this.maxCompositeBufferComponents = SETTING_HTTP_NETTY_MAX_COMPOSITE_BUFFER_COMPONENTS.get(settings);
        this.port = SETTING_HTTP_PORT.get(settings);
        // we can't make the network.bind_host a fallback since we already fall back to http.host hence the extra conditional here
        List<String> httpBindHost = SETTING_HTTP_BIND_HOST.get(settings);
        this.bindHosts = (httpBindHost.isEmpty() ? NetworkService.GLOBAL_NETWORK_BIND_HOST_SETTING.get(settings) : httpBindHost)
            .toArray(Strings.EMPTY_ARRAY);
        // we can't make the network.publish_host a fallback since we already fall back to http.host hence the extra conditional here
        List<String> httpPublishHost = SETTING_HTTP_PUBLISH_HOST.get(settings);
        this.publishHosts = (httpPublishHost.isEmpty() ? NetworkService.GLOBAL_NETWORK_PUBLISH_HOST_SETTING.get(settings) : httpPublishHost)
            .toArray(Strings.EMPTY_ARRAY);
        this.detailedErrorsEnabled = SETTING_HTTP_DETAILED_ERRORS_ENABLED.get(settings);
        this.readTimeoutMillis = Math.toIntExact(SETTING_HTTP_READ_TIMEOUT.get(settings).millis());

        ByteSizeValue receivePredictor = SETTING_HTTP_NETTY_RECEIVE_PREDICTOR_SIZE.get(settings);
        recvByteBufAllocator = new FixedRecvByteBufAllocator(receivePredictor.bytesAsInt());

        this.compression = SETTING_HTTP_COMPRESSION.get(settings);
        this.compressionLevel = SETTING_HTTP_COMPRESSION_LEVEL.get(settings);
        this.pipeliningMaxEvents = SETTING_PIPELINING_MAX_EVENTS.get(settings);
        this.corsConfig = buildCorsConfig(settings);

        logger.debug("using max_chunk_size[{}], max_header_size[{}], max_initial_line_length[{}], max_content_length[{}], " +
                "receive_predictor[{}], max_composite_buffer_components[{}], pipelining_max_events[{}]",
            maxChunkSize, maxHeaderSize, maxInitialLineLength, maxContentLength,
            receivePredictor, maxCompositeBufferComponents, pipeliningMaxEvents);
    }

    public Settings settings() {
        return this.settings;
    }

    @Override
    protected void doStart() {
        boolean success = false;
        try {
            inboundStatsHandler = new Netty4InboundStatsHandler(statsTracker, logger);
            outboundStatsHandler = new Netty4OutboundStatsHandler(statsTracker, logger);
            eventLoopGroup = NettyBootstrap.newEventLoopGroup(settings);
            serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(eventLoopGroup);
            serverBootstrap.channel(NettyBootstrap.serverChannel());

            serverBootstrap.childHandler(configureServerChannelHandler());

            serverBootstrap.childOption(ChannelOption.TCP_NODELAY, SETTING_HTTP_TCP_NO_DELAY.get(settings));
            serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, SETTING_HTTP_TCP_KEEP_ALIVE.get(settings));

            final ByteSizeValue tcpSendBufferSize = SETTING_HTTP_TCP_SEND_BUFFER_SIZE.get(settings);
            if (tcpSendBufferSize.getBytes() > 0) {
                serverBootstrap.childOption(ChannelOption.SO_SNDBUF, Math.toIntExact(tcpSendBufferSize.getBytes()));
            }

            final ByteSizeValue tcpReceiveBufferSize = SETTING_HTTP_TCP_RECEIVE_BUFFER_SIZE.get(settings);
            if (tcpReceiveBufferSize.getBytes() > 0) {
                serverBootstrap.childOption(ChannelOption.SO_RCVBUF, Math.toIntExact(tcpReceiveBufferSize.getBytes()));
            }

            serverBootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);
            serverBootstrap.childOption(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);

            final boolean reuseAddress = SETTING_HTTP_TCP_REUSE_ADDRESS.get(settings);
            serverBootstrap.option(ChannelOption.SO_REUSEADDR, reuseAddress);
            serverBootstrap.childOption(ChannelOption.SO_REUSEADDR, reuseAddress);

            this.boundAddress = createBoundHttpAddress();
            if (logger.isInfoEnabled()) {
                logger.info("{}", boundAddress);
            }
            success = true;
        } finally {
            if (success == false) {
                doStop(); // otherwise we leak threads since we never moved to started
            }
        }
    }

    private BoundTransportAddress createBoundHttpAddress() {
        // Bind and start to accept incoming connections.
        List<InetAddress> hostAddresses;
        try {
            hostAddresses = networkService.resolveBindHostAddresses(bindHosts);
        } catch (IOException e) {
            throw new BindHttpException("Failed to resolve host [" + Arrays.toString(bindHosts) + "]", e);
        }

        List<TransportAddress> boundAddresses = new ArrayList<>(hostAddresses.size());
        for (InetAddress address : hostAddresses) {
            boundAddresses.add(bindAddress(address));
        }

        final InetAddress publishInetAddress;
        try {
            publishInetAddress = networkService.resolvePublishHostAddresses(publishHosts);
        } catch (Exception e) {
            throw new BindTransportException("Failed to resolve publish address", e);
        }

        final int publishPort = resolvePublishPort(settings, boundAddresses, publishInetAddress);
        final InetSocketAddress publishAddress = new InetSocketAddress(publishInetAddress, publishPort);
        return new BoundTransportAddress(boundAddresses.toArray(new TransportAddress[0]), new TransportAddress(publishAddress));
    }

    // package private for tests
    static int resolvePublishPort(Settings settings, List<TransportAddress> boundAddresses, InetAddress publishInetAddress) {
        int publishPort = SETTING_HTTP_PUBLISH_PORT.get(settings);

        if (publishPort < 0) {
            for (TransportAddress boundAddress : boundAddresses) {
                InetAddress boundInetAddress = boundAddress.address().getAddress();
                if (boundInetAddress.isAnyLocalAddress() || boundInetAddress.equals(publishInetAddress)) {
                    publishPort = boundAddress.getPort();
                    break;
                }
            }
        }

        // if no matching boundAddress found, check if there is a unique port for all bound addresses
        if (publishPort < 0) {
            final IntSet ports = new IntHashSet();
            for (TransportAddress boundAddress : boundAddresses) {
                ports.add(boundAddress.getPort());
            }
            if (ports.size() == 1) {
                publishPort = ports.iterator().next().value;
            }
        }

        if (publishPort < 0) {
            throw new BindHttpException("Failed to auto-resolve http publish port, multiple bound addresses " + boundAddresses +
                " with distinct ports and none of them matched the publish address (" + publishInetAddress + "). " +
                "Please specify a unique port by setting " + SETTING_HTTP_PORT.getKey() + " or " + SETTING_HTTP_PUBLISH_PORT.getKey());
        }
        return publishPort;
    }

    // package private for testing
    static Netty4CorsConfig buildCorsConfig(Settings settings) {
        if (SETTING_CORS_ENABLED.get(settings) == false) {
            return Netty4CorsConfigBuilder.forOrigins().disable().build();
        }
        String origin = SETTING_CORS_ALLOW_ORIGIN.get(settings);
        final Netty4CorsConfigBuilder builder;
        if (Strings.isNullOrEmpty(origin)) {
            builder = Netty4CorsConfigBuilder.forOrigins();
        } else if (origin.equals(ANY_ORIGIN)) {
            builder = Netty4CorsConfigBuilder.forAnyOrigin();
        } else {
            try {
                Pattern p = RestUtils.checkCorsSettingForRegex(origin);
                if (p == null) {
                    builder = Netty4CorsConfigBuilder.forOrigins(RestUtils.corsSettingAsArray(origin));
                } else {
                    builder = Netty4CorsConfigBuilder.forPattern(p);
                }
            } catch (PatternSyntaxException e) {
                throw new SettingsException("Bad regex in [" + SETTING_CORS_ALLOW_ORIGIN.getKey() + "]: [" + origin + "]", e);
            }
        }
        if (SETTING_CORS_ALLOW_CREDENTIALS.get(settings)) {
            builder.allowCredentials();
        }
        String[] strMethods = Strings.tokenizeToStringArray(SETTING_CORS_ALLOW_METHODS.get(settings), ",");
        HttpMethod[] methods = Arrays.stream(strMethods)
            .map(HttpMethod::valueOf)
            .toArray(HttpMethod[]::new);
        return builder.allowedRequestMethods(methods)
            .maxAge(SETTING_CORS_MAX_AGE.get(settings))
            .allowedRequestHeaders(Strings.tokenizeToStringArray(SETTING_CORS_ALLOW_HEADERS.get(settings), ","))
            .shortCircuit()
            .build();
    }

    private TransportAddress bindAddress(final InetAddress hostAddress) {
        final AtomicReference<Exception> lastException = new AtomicReference<>();
        final AtomicReference<InetSocketAddress> boundSocket = new AtomicReference<>();
        boolean success = port.iterate(portNumber -> {
            try {
                synchronized (serverChannels) {
                    ChannelFuture future = serverBootstrap.bind(new InetSocketAddress(hostAddress, portNumber)).sync();
                    serverChannels.add(future.channel());
                    boundSocket.set((InetSocketAddress) future.channel().localAddress());
                }
            } catch (Exception e) {
                lastException.set(e);
                return false;
            }
            return true;
        });
        if (!success) {
            throw new BindHttpException("Failed to bind to [" + port.getPortRangeString() + "]", lastException.get());
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Bound http to address {{}}", NetworkAddress.format(boundSocket.get()));
        }
        return new TransportAddress(boundSocket.get());
    }

    @Override
    protected void doStop() {
        synchronized (serverChannels) {
            if (!serverChannels.isEmpty()) {
                try {
                    Netty4Utils.closeChannels(serverChannels);
                } catch (IOException e) {
                    logger.trace("exception while closing channels", e);
                }
                serverChannels.clear();
            }
        }

        if (inboundStatsHandler != null) {
            inboundStatsHandler.close();
            inboundStatsHandler = null;
        }
        if (outboundStatsHandler != null) {
            outboundStatsHandler.close();
            outboundStatsHandler = null;
        }
        if (eventLoopGroup != null) {
            Future<?> future = eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            future.awaitUninterruptibly();
            eventLoopGroup = null;
        }

        if (serverBootstrap != null) {
            serverBootstrap = null;
        }
    }

    @Override
    protected void doClose() {
    }

    @Override
    public BoundTransportAddress boundAddress() {
        return this.boundAddress;
    }

    @Override
    public ConnectionStats stats() {
        return statsTracker.stats();
    }

    public Netty4CorsConfig getCorsConfig() {
        return corsConfig;
    }

    public ChannelHandler configureServerChannelHandler() {
        return new HttpChannelHandler(
            this,
            sessions,
            authentication,
            roles,
            breakerService,
            blobService,
            nodeClient,
            settings,
            sslContextProvider
        );
    }

    public static class HttpChannelHandler extends ChannelInitializer<Channel> {

        private final Netty4HttpServerTransport transport;
        private final NodeClient nodeClient;
        private final String nodeName;
        private final Path home;
        private BlobService blobService;
        private final Sessions sessions;
        private final Authentication authentication;
        private final Roles roles;
        private final CircuitBreakerService breakerService;
        private final Settings settings;
        private final SslContextProvider sslContextProvider;

        protected HttpChannelHandler(Netty4HttpServerTransport transport,
                                     Sessions sessions,
                                     Authentication authentication,
                                     Roles roles,
                                     CircuitBreakerService breakerService,
                                     BlobService blobService,
                                     NodeClient nodeClient,
                                     Settings settings,
                                     SslContextProvider sslContextProvider) {
            this.sessions = sessions;
            this.authentication = authentication;
            this.roles = roles;
            this.breakerService = breakerService;
            this.blobService = blobService;
            this.transport = transport;
            this.nodeClient = nodeClient;
            this.settings = settings;
            this.sslContextProvider = sslContextProvider;
            this.nodeName = NODE_NAME_SETTING.get(settings);
            this.home = PathUtils.get(PATH_HOME_SETTING.get(settings)).normalize();
        }

        @Override
        public void initChannel(Channel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            if (SslSettings.isHttpsEnabled(settings)) {
                SslContext sslContext = sslContextProvider.getServerContext(Protocol.HTTP);
                if (sslContext != null) {
                    pipeline.addFirst(sslContext.newHandler(ch.alloc()));
                }
            }
            pipeline.addLast("inbound_stats", transport.inboundStatsHandler);
            pipeline.addLast("outbound_stats", transport.outboundStatsHandler);
            pipeline.addLast("read_timeout", new ReadTimeoutHandler(transport.readTimeoutMillis, TimeUnit.MILLISECONDS));
            final HttpRequestDecoder decoder = new HttpRequestDecoder(
                Math.toIntExact(transport.maxInitialLineLength.getBytes()),
                Math.toIntExact(transport.maxHeaderSize.getBytes()),
                Math.toIntExact(transport.maxChunkSize.getBytes()));
            decoder.setCumulator(ByteToMessageDecoder.COMPOSITE_CUMULATOR);
            pipeline.addLast("decoder", decoder);
            pipeline.addLast("decoder_compress", new HttpContentDecompressor());
            pipeline.addLast("encoder", new HttpResponseEncoder());
            final HttpObjectAggregator aggregator = new HttpObjectAggregator(Math.toIntExact(transport.maxContentLength.getBytes()));
            aggregator.setMaxCumulationBufferComponents(transport.maxCompositeBufferComponents);
            pipeline.addLast("chunked", new ChunkedWriteHandler());
            pipeline.addLast("auth_handler", new HttpAuthUpstreamHandler(settings, authentication, roles));
            pipeline.addLast("blob_handler", new HttpBlobHandler(blobService));
            pipeline.addLast("aggregator", aggregator);
            if (transport.compression) {
                pipeline.addLast("encoder_compress", new HttpContentCompressor(transport.compressionLevel));
            }
            pipeline.addLast("sql_handler", new SqlHttpHandler(
                settings,
                sessions,
                breakerService::getBreaker,
                roles
            ));
            pipeline.addLast("handler", new MainAndStaticFileHandler(
                nodeName,
                home,
                nodeClient
            ));
            if (SETTING_CORS_ENABLED.get(transport.settings())) {
                pipeline.addAfter("encoder", "cors", new Netty4CorsHandler(transport.getCorsConfig()));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            Exceptions.maybeDieOnAnotherThread(cause);
            super.exceptionCaught(ctx, cause);
        }
    }


    public static InetAddress getRemoteAddress(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress isa) {
            return isa.getAddress();
        }
        // In certain cases the channel is an EmbeddedChannel (e.g. in tests)
        // and this type of channel has an EmbeddedSocketAddress instance as remoteAddress
        // which does not have an address.
        // An embedded socket address is handled like a local connection via loopback.
        return InetAddresses.forString("127.0.0.1");
    }
}
