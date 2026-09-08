package com.familyfinance.ai;

import com.familyfinance.family.CurrentMembership;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
final class PersonalAiGateway implements AiGateway {
    private final AiSettingsService settings;
    private final AiTransport transport;
    private final CurrentMembership membership;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<Long, Long> lastCall = new HashMap<>();
    private final Semaphore slots = new Semaphore(4);
    PersonalAiGateway(AiSettingsService settings, AiTransport transport, CurrentMembership membership, ObjectMapper mapper, Clock clock) {
        this.settings = settings; this.transport = transport; this.membership = membership; this.mapper = mapper; this.clock = clock;
    }
    record ConnectionResult(boolean reachable, boolean modelListed, String message) {}
    ConnectionResult test(Authentication auth, boolean confirmed) {
        requireConsent(confirmed);
        var credential = settings.credential(auth);
        acquire(auth);
        try {
            JsonNode node = json(transport.exchange(URI.create(credential.baseUrl() + "/models"), credential.key(), null));
            if (!node.path("data").isArray()) throw invalidResponse();
            boolean found = false;
            for (JsonNode model : node.path("data")) if (credential.model().equals(model.path("id").asText())) found = true;
            return new ConnectionResult(true, found, found ? "连接成功，模型目录包含所选模型；尚未测试推理" : "连接成功，但目录未列出所选模型；尚未测试推理");
        } finally { slots.release(); }
    }
    @Override public String complete(Authentication auth, Prompt prompt) {
        if (prompt == null) throw AiFailure.invalid();
        requireConsent(prompt.userApprovedExternalProcessing());
        if (prompt.text() == null || prompt.text().isBlank() || prompt.text().length() > 8000) throw AiFailure.invalid();
        var credential = settings.credential(auth);
        byte[] payload = mapper.writeValueAsBytes(Map.of("model", credential.model(), "stream", false, "max_tokens", 256,
                "messages", List.of(Map.of("role", "user", "content", prompt.text()))));
        acquire(auth);
        try {
            JsonNode node = json(transport.exchange(URI.create(credential.baseUrl() + "/chat/completions"), credential.key(), payload));
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            if (!content.isString() || content.asText().isBlank() || content.asText().contains(credential.key())) throw invalidResponse();
            return content.asText();
        } finally { java.util.Arrays.fill(payload, (byte) 0); slots.release(); }
    }
    private JsonNode json(byte[] bytes) {
        try { return mapper.readTree(bytes); }
        catch (RuntimeException e) { throw invalidResponse(); }
    }
    private static AiFailure invalidResponse() { return new AiFailure(502, "AI_INVALID_RESPONSE", "AI 服务返回格式不兼容"); }
    private static void requireConsent(boolean approved) {
        if (!approved) throw new AiFailure(400, "AI_CONSENT_REQUIRED", "请先确认此次外部请求及可能产生的服务商费用");
    }
    private void acquire(Authentication auth) {
        long userId = membership.require(auth).userId();
        synchronized (lastCall) {
            long now = clock.millis();
            lastCall.entrySet().removeIf(e -> now - e.getValue() >= 30_000);
            if (lastCall.containsKey(userId) || lastCall.size() >= 10_000 || !slots.tryAcquire())
                throw new AiFailure(429, "AI_RATE_LIMITED", "请求较频繁，请在 30 秒后重试");
            lastCall.put(userId, now);
        }
    }
}
