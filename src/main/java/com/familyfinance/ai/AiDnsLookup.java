package com.familyfinance.ai;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Caller timeout and bounded isolation even if the operating system ignores interruption. */
@Component
final class AiDnsLookup {
    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final Resolver resolver;
    private final ThreadPoolExecutor executor;
    private final long timeoutNanos;
    @Autowired AiDnsLookup() { this(InetAddress::getAllByName, 4, Duration.ofSeconds(3)); }
    AiDnsLookup(Resolver resolver, int capacity, Duration timeout) {
        this.resolver = resolver;
        timeoutNanos = timeout.toNanos();
        executor = new ThreadPoolExecutor(capacity, capacity, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            var thread = new Thread(r, "ai-dns-lookup"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }
    InetAddress[] resolve(String host, long deadlineNanos) throws UnknownHostException {
        long remaining = Math.min(timeoutNanos, deadlineNanos - System.nanoTime());
        if (remaining <= 0) throw unavailable();
        Future<InetAddress[]> task;
        try { task = executor.submit(() -> resolver.resolve(host)); }
        catch (RejectedExecutionException e) { throw unavailable(); }
        try {
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException e) { throw unavailable(); }
        finally { if (!task.isDone()) task.cancel(true); }
    }
    private static UnknownHostException unavailable() { return new UnknownHostException("AI endpoint unavailable"); }
    @PreDestroy void close() { executor.shutdownNow(); }
}
