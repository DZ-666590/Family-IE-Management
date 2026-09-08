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
@SpringBootTest(properties = {"app.seed.enabled=true", "app.ai.encryption-key="})
@AutoConfigureMockMvc
@Transactional
class AiStorageUnavailableApiTest {
    @Autowired MockMvc mvc;
    @Test void missingServerKeyDisablesSavingWithoutBreakingLoginOrDeletion() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/me/ai-settings").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.storageReady").value(false));
        mvc.perform(put("/api/me/ai-settings").session(session).secure(true).with(csrf()).contentType("application/json")
                .content("{\"baseUrl\":\"https://api.example.com/v1\",\"model\":\"sample\",\"apiKey\":\"test-secret\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("AI_STORAGE_UNAVAILABLE"));
        mvc.perform(delete("/api/me/ai-settings").session(session).with(csrf())).andExpect(status().isNoContent());
    }
}
