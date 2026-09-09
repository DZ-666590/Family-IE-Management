package com.familyfinance.ai;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
final class SafeAiTransport implements AiTransport {
    static final int MAX_RESPONSE_BYTES = 65_536;
    private final AiEndpointPolicy policy;
    private final AiDnsLookup dns;
    private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ai-request-deadline"); thread.setDaemon(true); return thread;
    });
    SafeAiTransport(AiEndpointPolicy policy, AiDnsLookup dns) { this.policy = policy; this.dns = dns; }
    @PreDestroy void close() { deadline.shutdownNow(); }

    @Override public byte[] exchange(URI endpoint, String key, byte[] body) {
        policy.validate(endpoint.toString());
        var request = new HttpUriRequestBase(body == null ? "GET" : "POST", endpoint);
        request.setHeader("Authorization", "Bearer " + key);
        request.setHeader("Accept", "application/json");
        if (body != null) request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        int totalSeconds = body == null ? 20 : 90;
        int readSeconds = body == null ? 10 : 60;
        long expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(totalSeconds);
        var cancel = deadline.schedule(request::cancel, totalSeconds, TimeUnit.SECONDS);
        // Resolver returns the validated addresses directly to the socket connector (no second lookup).
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicDnsResolver(dns, expiresAt))
                .setMaxConnTotal(1).setMaxConnPerRoute(1)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(readSeconds)).build()).build();
        try (var client = HttpClients.custom().setConnectionManager(manager)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofSeconds(readSeconds)).build()).build()) {
            return client.execute(request, response -> {
                if (response.getCode() < 200 || response.getCode() >= 300 || response.getEntity() == null) {
                    request.cancel();
                    throw new AiFailure(502, "AI_PROVIDER_REJECTED", "AI 服务未接受请求，请检查地址、模型及密钥");
                }
                byte[] bytes = response.getEntity().getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    request.cancel();
                    throw new AiFailure(502, "AI_RESPONSE_TOO_LARGE", "AI 服务返回内容过大");
                }
                return bytes;
            });
        } catch (AiFailure e) { throw e; }
        catch (IOException | RuntimeException e) {
            // Discard raw errors, headers and provider bodies. They can contain credentials/prompts.
            throw new AiFailure(502, "AI_CONNECTION_FAILED", "无法连接 AI 服务，请检查配置或稍后重试");
        } finally { cancel.cancel(false); request.removeHeaders("Authorization"); request.setEntity(null); }
    }
    static class PublicDnsResolver implements DnsResolver {
        private final AiDnsLookup lookup;
        private final long deadlineNanos;
        PublicDnsResolver(AiDnsLookup lookup, long deadlineNanos) { this.lookup = lookup; this.deadlineNanos = deadlineNanos; }
        @Override public InetAddress[] resolve(String host) throws UnknownHostException {
            return checked(lookup.resolve(host, deadlineNanos));
        }
        static InetAddress[] checked(InetAddress[] addresses) throws UnknownHostException {
            if (addresses.length == 0) throw new UnknownHostException("AI endpoint unavailable");
            for (InetAddress address : addresses) if (!AiEndpointPolicy.isPublic(address))
                throw new UnknownHostException("AI endpoint unavailable");
            return addresses;
        }
        @Override public String resolveCanonicalHostname(String host) { return host; }
    }
}
