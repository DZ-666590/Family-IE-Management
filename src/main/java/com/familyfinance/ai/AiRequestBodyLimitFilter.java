package com.familyfinance.ai;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Bounded credential input, including requests without Content-Length. */
@Component
@org.springframework.core.annotation.Order(-98) // After Spring Security.
final class AiRequestBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_BYTES = 16_384;
    private final ObjectMapper mapper;
    AiRequestBodyLimitFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = org.springframework.web.util.ServletRequestPathUtils.parse(request).pathWithinApplication().value();
        return !(path.equals("/api/me/ai-settings") || path.equals("/api/me/ai-settings/test"))
                || !(request.getMethod().equals("PUT") || request.getMethod().equals("POST"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) { reject(response); return; }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) { reject(response); return; }
        var input = new ByteArrayInputStream(bytes);
        try {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Async input unsupported"); }
                    };
                }
            }, response);
        } finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), ApiEnvelope.error(ApiError.of("AI_REQUEST_TOO_LARGE", "AI 配置请求内容过大")));
    }
}
