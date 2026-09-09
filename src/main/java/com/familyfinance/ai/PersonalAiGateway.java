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
    private final AiSystemConfiguration settings;
    private final AiTransport transport;
    private final CurrentMembership membership;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<Long, Long> lastCall = new HashMap<>();
    private final Map<Long, Integer> dailyUsage = new HashMap<>();
    private java.time.LocalDate usageDay;
    private int dailyTotal;
    private final Semaphore slots = new Semaphore(4);
    PersonalAiGateway(AiSystemConfiguration settings, AiTransport transport, CurrentMembership membership, ObjectMapper mapper, Clock clock) {
        this.settings = settings; this.transport = transport; this.membership = membership; this.mapper = mapper; this.clock = clock;
    }
    record ConnectionResult(boolean reachable, boolean modelListed, String message) {}
    ConnectionResult test(Authentication auth, boolean confirmed) {
        requireConsent(confirmed);
        membership.require(auth);
        var credential = settings.credential();
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
        membership.require(auth);
        var credential = settings.credential();
        Object messageContent = content(prompt);
        acquire(auth);
        byte[] payload = null;
        try {
            payload = mapper.writeValueAsBytes(Map.of("model", credential.model(), "stream", false, "max_tokens", 4096,
                    "enable_thinking", false, "messages", List.of(Map.of("role", "user", "content", messageContent))));
            JsonNode node = json(transport.exchange(URI.create(credential.baseUrl() + "/chat/completions"), credential.key(), payload));
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            if (!"stop".equals(node.path("choices").path(0).path("finish_reason").asText()))
                throw new AiFailure(502, "AI_INCOMPLETE_RESPONSE", "AI 返回内容未完整生成，请缩小文档范围后重试");
            if (!content.isString() || content.asText().isBlank() || content.asText().contains(credential.key())) throw invalidResponse();
            return content.asText();
        } finally { if (payload != null) java.util.Arrays.fill(payload, (byte) 0); slots.release(); }
    }
    private Object content(Prompt prompt) {
        if (prompt.images().isEmpty()) return prompt.text();
        if (prompt.images().size() > 4) throw AiFailure.invalid();
        var content = new java.util.ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", prompt.text()));
        int total = 0;
        for (var image : prompt.images()) {
            byte[] bytes = image.bytes(); total += bytes.length;
            if (bytes.length == 0 || bytes.length > 2_000_000 || total > 6_000_000
                    || !("image/png".equals(image.mimeType()) || "image/jpeg".equals(image.mimeType()))) throw AiFailure.invalid();
            try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
                var readers = javax.imageio.ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw AiFailure.invalid();
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName();
                    if (!(image.mimeType().equals("image/png") ? format.equalsIgnoreCase("png") : format.equalsIgnoreCase("JPEG"))
                            || reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0
                            || (long)reader.getWidth(0) * reader.getHeight(0) > 20_000_000) throw AiFailure.invalid();
                } finally { reader.dispose(); }
            } catch (java.io.IOException e) { throw AiFailure.invalid(); }
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", "data:" + image.mimeType() + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes))));
        }
        return content;
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
            var today = java.time.LocalDate.now(clock);
            if (!today.equals(usageDay)) { dailyUsage.clear(); dailyTotal = 0; usageDay = today; }
            if (dailyUsage.getOrDefault(userId, 0) >= 20 || dailyTotal >= 200)
                throw new AiFailure(429, "AI_DAILY_LIMIT", "今日 AI 请求额度已用完，请明天再试");
            lastCall.entrySet().removeIf(e -> now - e.getValue() >= 30_000);
            if (lastCall.containsKey(userId) || lastCall.size() >= 10_000 || !slots.tryAcquire())
                throw new AiFailure(429, "AI_RATE_LIMITED", "请求较频繁，请在 30 秒后重试");
            lastCall.put(userId, now);
            dailyUsage.merge(userId, 1, Integer::sum); dailyTotal++;
        }
    }
}
