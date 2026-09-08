package com.familyfinance.ai;

import static org.assertj.core.api.Assertions.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AiDnsLookupTest {
    @Test void stuckSystemDnsCannotHoldCallerOrAccumulateQueuedWork() throws Exception {
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var lookup = new AiDnsLookup(host -> {
            calls.incrementAndGet();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* Simulates uninterruptible OS DNS. */ }
            }
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};
        }, 1, Duration.ofMillis(80));
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> lookup.resolve("api.example.com", System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
                    .isInstanceOf(UnknownHostException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
            assertThat(calls).hasValue(1);
            assertThatThrownBy(() -> lookup.resolve("another.example.com", System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
                    .isInstanceOf(UnknownHostException.class);
            assertThat(calls).hasValue(1);
        } finally { release.countDown(); lookup.close(); }
    }
    @Test void expiredRequestDeadlineNeverStartsDns() {
        var calls = new AtomicInteger();
        var lookup = new AiDnsLookup(host -> { calls.incrementAndGet(); return new InetAddress[0]; }, 1, Duration.ofSeconds(3));
        try {
            assertThatThrownBy(() -> lookup.resolve("api.example.com", System.nanoTime() - 1)).isInstanceOf(UnknownHostException.class);
            assertThat(calls).hasValue(0);
        } finally { lookup.close(); }
    }
}
