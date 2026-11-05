package org.smartgurukul.chhaon.scheduler;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalHeartbeatTest {

    @Test
    void blankOrUnsafeUrlMeansOff() {
        assertThat(new ExternalHeartbeat("").enabled()).isFalse();
        assertThat(new ExternalHeartbeat("   ").enabled()).isFalse();
        assertThat(new ExternalHeartbeat("http://example.com/hb").enabled()).isFalse(); // plain http only for localhost
        assertThat(new ExternalHeartbeat("not a url").enabled()).isFalse();
        assertThat(new ExternalHeartbeat("https://uptime.betterstack.com/api/v1/heartbeat/abc").enabled()).isTrue();
        new ExternalHeartbeat("").ping(); // off: does nothing, never throws
    }

    @Test
    void pingCallsTheUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch hit = new CountDownLatch(1);
        server.createContext("/hb", ex -> {
            hit.countDown();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            ExternalHeartbeat hb = new ExternalHeartbeat("http://127.0.0.1:" + server.getAddress().getPort() + "/hb");
            assertThat(hb.enabled()).isTrue();
            hb.ping();
            assertThat(hit.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableWatcherNeverThrows() {
        new ExternalHeartbeat("http://127.0.0.1:1/hb").ping();
    }
}
