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

package org.elasticsearch.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.action.support.PlainFuture;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.CloseableChannel;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockPageCacheRecycler;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.MockLogAppender;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.Test;

import io.netty.channel.embedded.EmbeddedChannel;

/** Unit tests for {@link TcpTransport} */
public class TcpTransportTests extends ESTestCase {

    /** Test ipv4 host with a default port works */
    @Test
    public void testParseV4DefaultPort() throws Exception {
        TransportAddress[] addresses = TcpTransport.parse("127.0.0.1", 1234);
        assertThat(addresses.length).isEqualTo(1);

        assertThat(addresses[0].getAddress()).isEqualTo("127.0.0.1");
        assertThat(addresses[0].getPort()).isEqualTo(1234);
    }

    /** Test ipv4 host with port works */
    @Test
    public void testParseV4WithPort() throws Exception {
        TransportAddress[] addresses = TcpTransport.parse("127.0.0.1:2345", 1234);
        assertThat(addresses.length).isEqualTo(1);

        assertThat(addresses[0].getAddress()).isEqualTo("127.0.0.1");
        assertThat(addresses[0].getPort()).isEqualTo(2345);
    }

    /** Test unbracketed ipv6 hosts in configuration fail. Leave no ambiguity */
    @Test
    public void testParseV6UnBracketed() throws Exception {
        try {
            TcpTransport.parse("::1", 1234);
            fail("should have gotten exception");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage().contains("must be bracketed")).isTrue();
        }
    }

    /** Test ipv6 host with a default port works */
    @Test
    public void testParseV6DefaultPort() throws Exception {
        TransportAddress[] addresses = TcpTransport.parse("[::1]", 1234);
        assertThat(addresses.length).isEqualTo(1);

        assertThat(addresses[0].getAddress()).isEqualTo("::1");
        assertThat(addresses[0].getPort()).isEqualTo(1234);
    }

    /** Test ipv6 host with port works */
    @Test
    public void testParseV6WithPort() throws Exception {
        TransportAddress[] addresses = TcpTransport.parse("[::1]:2345", 1234);
        assertThat(addresses.length).isEqualTo(1);

        assertThat(addresses[0].getAddress()).isEqualTo("::1");
        assertThat(addresses[0].getPort()).isEqualTo(2345);
    }

    @Test
    public void testRejectsPortRanges() {
        assertThatThrownBy(() -> TcpTransport.parse("[::1]:100-200", 1000))
            .isExactlyInstanceOf(NumberFormatException.class);
    }

    @Test
    public void testDefaultSeedAddressesWithDefaultPort() {
        final List<String> seedAddresses = NetworkUtils.SUPPORTS_V6 ?
            List.of(
                "[::1]:4300", "[::1]:4301", "[::1]:4302", "[::1]:4303", "[::1]:4304", "[::1]:4305",
                "127.0.0.1:4300", "127.0.0.1:4301", "127.0.0.1:4302", "127.0.0.1:4303", "127.0.0.1:4304", "127.0.0.1:4305") :
            List.of(
                "127.0.0.1:4300", "127.0.0.1:4301", "127.0.0.1:4302", "127.0.0.1:4303", "127.0.0.1:4304", "127.0.0.1:4305");
        testDefaultSeedAddresses(Settings.EMPTY, seedAddresses);
    }

    @Test
    public void testDefaultSeedAddressesWithNonstandardGlobalPortRange() {
        final List<String> seedAddresses = NetworkUtils.SUPPORTS_V6 ?
            List.of("[::1]:4500", "[::1]:4501", "[::1]:4502", "[::1]:4503", "[::1]:4504", "[::1]:4505",
                "127.0.0.1:4500", "127.0.0.1:4501", "127.0.0.1:4502", "127.0.0.1:4503", "127.0.0.1:4504", "127.0.0.1:4505") :
            List.of(
                "127.0.0.1:4500", "127.0.0.1:4501", "127.0.0.1:4502", "127.0.0.1:4503", "127.0.0.1:4504", "127.0.0.1:4505");
        testDefaultSeedAddresses(Settings.builder().put(TransportSettings.PORT.getKey(), "4500-9600").build(), seedAddresses);
    }

    @Test
    public void testDefaultSeedAddressesWithSmallGlobalPortRange() {
        final List<String> seedAddresses = NetworkUtils.SUPPORTS_V6 ?
            List.of("[::1]:4300", "[::1]:4301", "[::1]:4302", "127.0.0.1:4300", "127.0.0.1:4301", "127.0.0.1:4302") :
            List.of("127.0.0.1:4300", "127.0.0.1:4301", "127.0.0.1:4302");
        testDefaultSeedAddresses(Settings.builder().put(TransportSettings.PORT.getKey(), "4300-4302").build(), seedAddresses);
    }

    @Test
    public void testDefaultSeedAddressesWithNonstandardSinglePort() {
        testDefaultSeedAddresses(Settings.builder().put(TransportSettings.PORT.getKey(), "4500").build(),
            NetworkUtils.SUPPORTS_V6 ? List.of("[::1]:4500", "127.0.0.1:4500") : List.of("127.0.0.1:4500"));
    }

    private void testDefaultSeedAddresses(final Settings settings, List<String> expectedAddresses) {
        final TestThreadPool testThreadPool = new TestThreadPool("test");
        try {
            final TcpTransport tcpTransport = new TcpTransport(settings, Version.CURRENT, testThreadPool,
                new MockPageCacheRecycler(settings),
                new NoneCircuitBreakerService(), writableRegistry(), new NetworkService(Collections.emptyList())) {

                @Override
                protected CloseableChannel bind(InetSocketAddress address) {
                    throw new UnsupportedOperationException();
                }

                @Override
                protected ConnectResult initiateChannel(DiscoveryNode node) {
                    throw new UnsupportedOperationException();
                }

                @Override
                protected void stopInternal() {
                    throw new UnsupportedOperationException();
                }
            };

            assertThat(tcpTransport.getDefaultSeedAddresses()).containsExactlyInAnyOrder(expectedAddresses.toArray(new String[]{}));
        } finally {
            testThreadPool.shutdown();
        }
    }

    @Test
    public void testReadMessageLengthWithIncompleteHeader() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {
            streamOutput.write('E');
            streamOutput.write('S');
            streamOutput.write(1);
            streamOutput.write(1);

            assertThat(TcpTransport.readMessageLength(streamOutput.bytes())).isEqualTo(-1);
        }
    }

    @Test
    public void testReadPingMessageLength() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {
            streamOutput.write('E');
            streamOutput.write('S');
            streamOutput.writeInt(-1);

            assertThat(TcpTransport.readMessageLength(streamOutput.bytes())).isEqualTo(0);
        }
    }

    @Test
    public void testReadPingMessageLengthWithStartOfSecondMessage() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {
            streamOutput.write('E');
            streamOutput.write('S');
            streamOutput.writeInt(-1);
            streamOutput.write('E');
            streamOutput.write('S');

            assertThat(TcpTransport.readMessageLength(streamOutput.bytes())).isEqualTo(0);
        }
    }

    @Test
    public void testReadMessageLength() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {
            streamOutput.write('E');
            streamOutput.write('S');
            streamOutput.writeInt(2);
            streamOutput.write('M');
            streamOutput.write('A');

            assertThat(TcpTransport.readMessageLength(streamOutput.bytes())).isEqualTo(2);
        }
    }

    @Test
    public void testInvalidLength() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {
            streamOutput.write('E');
            streamOutput.write('S');
            streamOutput.writeInt(-2);
            streamOutput.write('M');
            streamOutput.write('A');

            assertThatThrownBy(() -> TcpTransport.readMessageLength(streamOutput.bytes()))
                .isExactlyInstanceOf(StreamCorruptedException.class)
                .hasMessage("invalid data length: -2");
        }
    }

    @Test
    public void testInvalidHeader() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {
            streamOutput.write('E');
            streamOutput.write('C');
            byte byte1 = randomByte();
            byte byte2 = randomByte();
            streamOutput.write(byte1);
            streamOutput.write(byte2);
            streamOutput.write(randomByte());
            streamOutput.write(randomByte());
            streamOutput.write(randomByte());

            String expected = "invalid internal transport message format, got (45,43,"
                + Integer.toHexString(byte1 & 0xFF) + ","
                + Integer.toHexString(byte2 & 0xFF) + ")";
            assertThatThrownBy(() -> TcpTransport.readMessageLength(streamOutput.bytes()))
                .isExactlyInstanceOf(StreamCorruptedException.class)
                .hasMessage(expected);
        }
    }

    @Test
    public void testHTTPRequest() throws IOException {
        String[] httpHeaders = {"GET", "POST", "PUT", "HEAD", "DELETE", "OPTIONS", "PATCH", "TRACE"};

        for (String httpHeader : httpHeaders) {
            try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {

                for (char c : httpHeader.toCharArray()) {
                    streamOutput.write((byte) c);
                }
                streamOutput.write(new byte[6]);

                assertThatThrownBy(() -> TcpTransport.readMessageLength(streamOutput.bytes()))
                    .isExactlyInstanceOf(TcpTransport.HttpRequestOnTransportException.class)
                    .hasMessage("This is not a HTTP port");
            }
        }
    }

    @Test
    public void testTLSHeader() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {

            streamOutput.write(0x16);
            streamOutput.write(0x03);
            byte byte1 = randomByte();
            streamOutput.write(byte1);
            byte byte2 = randomByte();
            streamOutput.write(byte2);
            streamOutput.write(randomByte());
            streamOutput.write(randomByte());
            streamOutput.write(randomByte());

            String expected = "SSL/TLS request received but SSL/TLS is not enabled on this node, got (16,3,"
                + Integer.toHexString(byte1 & 0xFF) + ","
                + Integer.toHexString(byte2 & 0xFF) + ")";

            assertThatThrownBy(() -> TcpTransport.readMessageLength(streamOutput.bytes()))
                .isExactlyInstanceOf(StreamCorruptedException.class)
                .hasMessage(expected);
        }
    }

    @Test
    public void testHTTPResponse() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {
            streamOutput.write('H');
            streamOutput.write('T');
            streamOutput.write('T');
            streamOutput.write('P');
            streamOutput.write(randomByte());
            streamOutput.write(randomByte());

            assertThatThrownBy(() -> TcpTransport.readMessageLength(streamOutput.bytes()))
                .isExactlyInstanceOf(StreamCorruptedException.class)
                .hasMessage("received HTTP response on transport port, ensure that transport port " +
                            "(not HTTP port) of a remote node is specified in the configuration");
        }
    }

    @TestLogging(value = "org.elasticsearch.transport.TcpTransport:DEBUG")
    @Test
    public void testExceptionHandling() throws IllegalAccessException {
        testExceptionHandling(false, new ElasticsearchException("simulated"),
            new MockLogAppender.UnseenEventExpectation("message", "org.elasticsearch.transport.TcpTransport", Level.ERROR, "*"),
            new MockLogAppender.UnseenEventExpectation("message", "org.elasticsearch.transport.TcpTransport", Level.WARN, "*"),
            new MockLogAppender.UnseenEventExpectation("message", "org.elasticsearch.transport.TcpTransport", Level.INFO, "*"),
            new MockLogAppender.UnseenEventExpectation("message", "org.elasticsearch.transport.TcpTransport", Level.DEBUG, "*"));
        testExceptionHandling(new ElasticsearchException("simulated"),
            new MockLogAppender.SeenEventExpectation("message", "org.elasticsearch.transport.TcpTransport",
                Level.WARN, "exception caught on transport layer [*], closing connection"));
        testExceptionHandling(new ClosedChannelException(),
            new MockLogAppender.SeenEventExpectation("message", "org.elasticsearch.transport.TcpTransport",
                Level.DEBUG, "close connection exception caught on transport layer [*], disconnecting from relevant node"));
        testExceptionHandling(new ElasticsearchException("Connection reset"),
            new MockLogAppender.SeenEventExpectation("message", "org.elasticsearch.transport.TcpTransport",
                Level.DEBUG, "close connection exception caught on transport layer [*], disconnecting from relevant node"));
        testExceptionHandling(new BindException(),
            new MockLogAppender.SeenEventExpectation("message", "org.elasticsearch.transport.TcpTransport",
                Level.DEBUG, "bind exception caught on transport layer [*]"));
        testExceptionHandling(new CancelledKeyException(),
            new MockLogAppender.SeenEventExpectation("message", "org.elasticsearch.transport.TcpTransport",
                Level.DEBUG, "cancelled key exception caught on transport layer [*], disconnecting from relevant node"));
        testExceptionHandling(true, new TcpTransport.HttpRequestOnTransportException("test"),
            new MockLogAppender.UnseenEventExpectation("message", "org.elasticsearch.transport.TcpTransport", Level.ERROR, "*"),
            new MockLogAppender.UnseenEventExpectation("message", "org.elasticsearch.transport.TcpTransport", Level.WARN, "*"),
            new MockLogAppender.UnseenEventExpectation("message", "org.elasticsearch.transport.TcpTransport", Level.INFO, "*"),
            new MockLogAppender.UnseenEventExpectation("message", "org.elasticsearch.transport.TcpTransport", Level.DEBUG, "*"));
        testExceptionHandling(new StreamCorruptedException("simulated"),
            new MockLogAppender.SeenEventExpectation("message", "org.elasticsearch.transport.TcpTransport",
                Level.WARN, "simulated, [*], closing connection"));
    }

    private void testExceptionHandling(Exception exception,
                                       MockLogAppender.LoggingExpectation... expectations) throws IllegalAccessException {
        testExceptionHandling(true, exception, expectations);
    }

    private void testExceptionHandling(boolean startTransport,
                                       Exception exception,
                                       MockLogAppender.LoggingExpectation... expectations) throws IllegalAccessException {
        final TestThreadPool testThreadPool = new TestThreadPool("test");
        MockLogAppender appender = new MockLogAppender();

        try {
            appender.start();

            Loggers.addAppender(LogManager.getLogger(TcpTransport.class), appender);
            for (MockLogAppender.LoggingExpectation expectation : expectations) {
                appender.addExpectation(expectation);
            }

            final Lifecycle lifecycle = new Lifecycle();

            if (startTransport) {
                lifecycle.moveToStarted();
            }

            EmbeddedChannel embeddedChannel = new EmbeddedChannel();
            CloseableChannel channel = new CloseableChannel(embeddedChannel, false);
            final PlainFuture<Void> listener = new PlainFuture<>();
            channel.addCloseListener(listener);

            var logger = LogManager.getLogger(TcpTransport.class);
            var outputHandler = new OutboundHandler(
                randomAlphaOfLength(10),
                Version.CURRENT,
                new StatsTracker(),
                testThreadPool,
                BigArrays.NON_RECYCLING_INSTANCE
            );
            TcpTransport.handleException(logger, channel, exception, lifecycle, outputHandler);

            assertThat(listener.isDone()).isTrue();
            assertThat(FutureUtils.get(listener)).isNull();

            appender.assertAllExpectationsMatched();

        } finally {
            Loggers.removeAppender(LogManager.getLogger(TcpTransport.class), appender);
            appender.stop();
            ThreadPool.terminate(testThreadPool, 30, TimeUnit.SECONDS);
        }
    }
}
