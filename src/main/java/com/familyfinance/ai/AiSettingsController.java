package com.familyfinance.ai;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/ai-settings")
class AiSettingsController {
    private final AiSettingsService service;
    private final PersonalAiGateway gateway;
    AiSettingsController(AiSettingsService service, PersonalAiGateway gateway) { this.service = service; this.gateway = gateway; }
    record TestRequest(boolean confirmed) {}
    @PostMapping("/test")
    ResponseEntity<ApiEnvelope<PersonalAiGateway.ConnectionResult>> test(Authentication auth, HttpServletRequest request,
                                                                        @RequestBody TestRequest input) {
        requireSecure(request);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.data(gateway.test(auth, input.confirmed())));
    }
    @GetMapping
    ResponseEntity<ApiEnvelope<AiSettingsService.View>> get(Authentication auth) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.data(service.read(auth)));
    }
    @PutMapping
    ResponseEntity<ApiEnvelope<AiSettingsService.View>> put(Authentication auth, HttpServletRequest request,
                                                          @RequestBody AiSettingsService.Update input) {
        requireSecure(request);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.data(service.save(auth, input)));
    }
    @DeleteMapping
    ResponseEntity<Void> delete(Authentication auth) { service.delete(auth); return ResponseEntity.noContent().build(); }
    static void requireSecure(HttpServletRequest request) {
        if (!request.isSecure()) throw new AiFailure(400, "AI_HTTPS_REQUIRED", "请通过 HTTPS 访问后再保存或测试 AI 配置");
    }
    @ExceptionHandler(AiFailure.class)
    ResponseEntity<ApiEnvelope<Void>> failure(AiFailure error) {
        return ResponseEntity.status(error.status).header("Cache-Control", "no-store")
                .body(ApiEnvelope.error(ApiError.of(error.code, error.getMessage())));
    }
}
