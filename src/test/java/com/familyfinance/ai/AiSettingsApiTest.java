package com.familyfinance.ai;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = {"app.seed.enabled=true", "app.ai.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.ai.allowed-hosts=api.example.com"})
@AutoConfigureMockMvc
@Transactional
class AiSettingsApiTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean AiTransport transport;
    @Autowired AiSettingsRepository settings;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired com.familyfinance.household.AppUserRepository users;
    @Test void configurationIsEncryptedAndIsolatedEvenWithinOneFamily() throws Exception {
        var owner = login();
        save(owner, "test-secret-one");
        var entity = settings.findById(users.findByUsername("demo").orElseThrow().getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(entity.encryptedKey).startsWith("v1.").doesNotContain("test-secret-one");
        String before = entity.encryptedKey;
        mvc.perform(put("/api/me/ai-settings").session(owner).secure(true).with(csrf()).contentType("application/json")
                .content("{\"baseUrl\":\"https://api.example.com/v1\",\"model\":\"changed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.model").value("changed"));
        org.assertj.core.api.Assertions.assertThat(entity.encryptedKey).isEqualTo(before);
        mvc.perform(put("/api/me/ai-settings").session(owner).secure(true).with(csrf()).contentType("application/json")
                .content("{\"baseUrl\":\"https://api.example.com/v2\",\"model\":\"changed\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("AI_KEY_REQUIRED"));
        String response = mvc.perform(post("/api/family/invites").session(owner).with(csrf()).contentType("application/json").content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content("""
                {"email":"ai-member@example.com","displayName":"AI member","password":"family-pass-2026","mode":"JOIN","inviteToken":"%s"}
                """.formatted(token))).andExpect(status().isCreated());
        var member = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).param("username", "ai-member@example.com").param("password", "family-pass-2026"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/me/ai-settings").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$.data.keyConfigured").value(false));
        save(member, "member-test-secret");
        mvc.perform(delete("/api/me/ai-settings").session(member).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/me/ai-settings").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.keyConfigured").value(true));
        mvc.perform(get("/api/me/ai-settings/1").session(member)).andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(transport);
    }
    @Test void connectionTestRequiresConsentUsesOnlyModelDirectoryAndIsRateLimited() throws Exception {
        var session = login();
        save(session, "test-directory-secret");
        mvc.perform(post("/api/me/ai-settings/test").session(session).secure(true).with(csrf()).contentType("application/json").content("{\"confirmed\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("AI_CONSENT_REQUIRED"));
        org.mockito.Mockito.verifyNoInteractions(transport);
        org.mockito.Mockito.when(transport.exchange(java.net.URI.create("https://api.example.com/v1/models"), "test-directory-secret", null))
                .thenReturn("{\"data\":[{\"id\":\"sample-model\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(post("/api/me/ai-settings/test").session(session).secure(true).with(csrf()).contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.modelListed").value(true));
        mvc.perform(post("/api/me/ai-settings/test").session(session).secure(true).with(csrf()).contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isTooManyRequests());
        org.mockito.Mockito.verify(transport).exchange(java.net.URI.create("https://api.example.com/v1/models"), "test-directory-secret", null);
    }
    @Test void rejectsOversizedInputWithoutReflectingItAndIgnoresSpoofedForwarding() throws Exception {
        var session = login();
        mvc.perform(put("/api/me/ai-settings").session(session).secure(true).with(csrf()).contentType("application/json")
                .content("{\"apiKey\":\"" + "x".repeat(17000) + "\"}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("AI_REQUEST_TOO_LARGE"));
        mvc.perform(put("/api/me/ai-settings").session(session).with(csrf()).header("X-Forwarded-Proto", "https")
                .contentType("application/json").content("{\"baseUrl\":\"https://api.example.com/v1\",\"model\":\"sample\",\"apiKey\":\"test-secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("AI_HTTPS_REQUIRED"));
    }
    @Test void nullTestRequestIsRejectedWithoutSending() throws Exception {
        mvc.perform(post("/api/me/ai-settings/test").session(login()).secure(true).with(csrf()).contentType("application/json").content("null"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(transport);
    }
    private void save(MockHttpSession session, String secret) throws Exception {
        mvc.perform(put("/api/me/ai-settings").session(session).secure(true).with(csrf()).contentType("application/json").content("""
                {"baseUrl":"https://api.example.com/v1","model":"sample-model","apiKey":"%s"}
                """.formatted(secret))).andExpect(status().isOk());
    }
    @Autowired MockMvc mvc;
    @Test void settingsArePrivateAndNeverReturnTheSecret() throws Exception {
        mvc.perform(get("/api/me/ai-settings")).andExpect(status().isUnauthorized());
        var session = login();
        mvc.perform(get("/api/me/ai-settings").session(session).secure(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.keyConfigured").value(false));
        mvc.perform(put("/api/me/ai-settings").session(session).secure(true).with(csrf())
                .contentType("application/json").content("""
                {"baseUrl":"https://api.example.com/v1","model":"sample-model","apiKey":"test-only-secret"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.keyConfigured").value(true))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
        mvc.perform(get("/api/me/ai-settings").session(session).secure(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.model").value("sample-model"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }
    @Test void csrfAndSecureTransportAreRequired() throws Exception {
        var session = login();
        String body = "{\"baseUrl\":\"https://api.example.com/v1\",\"model\":\"sample\",\"apiKey\":\"test-only-secret\"}";
        mvc.perform(put("/api/me/ai-settings").session(session).secure(true).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/me/ai-settings").session(session).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("AI_HTTPS_REQUIRED"));
    }
    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}
