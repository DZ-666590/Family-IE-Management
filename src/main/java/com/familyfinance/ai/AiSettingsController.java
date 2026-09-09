package com.familyfinance.ai;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.ApiError;
import com.familyfinance.family.CurrentMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/ai-settings")
class AiSettingsController {
    private final AiSystemConfiguration configuration;
    private final PersonalAiGateway gateway;
    private final CurrentMembership membership;
    AiSettingsController(AiSystemConfiguration configuration, PersonalAiGateway gateway, CurrentMembership membership) {
        this.configuration = configuration; this.gateway = gateway; this.membership = membership;
    }
    record Status(String provider, String model, boolean ready, int dailyRequestLimit) {}
    record TestRequest(boolean confirmed) {}
    @GetMapping
    ResponseEntity<ApiEnvelope<Status>> get(Authentication auth) {
        membership.require(auth);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiEnvelope.data(new Status("阿里云百炼", AiSystemConfiguration.MODEL, configuration.ready(), 20)));
    }
    @PostMapping("/test")
    ResponseEntity<ApiEnvelope<PersonalAiGateway.ConnectionResult>> test(Authentication auth, @RequestBody TestRequest input) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.data(gateway.test(auth, input.confirmed())));
    }
    @RequestMapping(method = {RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    ResponseEntity<ApiEnvelope<Void>> managed(Authentication auth) {
        membership.require(auth);
        return ResponseEntity.status(405).header("Cache-Control", "no-store").header("Allow", "GET, POST")
                .body(ApiEnvelope.error(ApiError.of("AI_SERVER_MANAGED", "AI 服务由服务器统一配置，个人账号不能更改")));
    }
    @ExceptionHandler(AiFailure.class)
    ResponseEntity<ApiEnvelope<Void>> failure(AiFailure error) {
        return ResponseEntity.status(error.status).header("Cache-Control", "no-store")
                .body(ApiEnvelope.error(ApiError.of(error.code, error.getMessage())));
    }
}
