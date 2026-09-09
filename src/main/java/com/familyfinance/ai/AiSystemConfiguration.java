package com.familyfinance.ai;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Credentials are supplied only by the server environment, never by an application user. */
@Component
final class AiSystemConfiguration {
    static final String MODEL = "qwen3.8-max";
    private final String baseUrl;
    private final String key;
    AiSystemConfiguration(@Value("${app.ai.base-url:}") String baseUrl, @Value("${app.ai.api-key:}") String key) {
        this.baseUrl = baseUrl.replaceAll("/$", ""); this.key = key;
    }
    static String officialHost(String url) {
        try {
            URI uri = URI.create(url.replaceAll("/$", ""));
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && (host.equals("dashscope.aliyuncs.com") || host.matches("[a-zA-Z0-9-]+\\.cn-beijing\\.maas\\.aliyuncs\\.com"))
                    && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getFragment() == null
                    && "/compatible-mode/v1".equals(uri.getRawPath()) ? host : "";
        } catch (IllegalArgumentException e) { return ""; }
    }
    boolean ready() { return !officialHost(baseUrl).isEmpty() && key.matches("[\\x21-\\x7E]{8,2000}"); }
    Credential credential() {
        if (!ready()) throw new AiFailure(503, "AI_SYSTEM_NOT_CONFIGURED", "系统 AI 服务尚未配置，请联系管理员");
        return new Credential(baseUrl, MODEL, key);
    }
    record Credential(String baseUrl, String model, String key) {
        @Override public String toString() { return "SystemAiCredential[REDACTED]"; }
    }
}
