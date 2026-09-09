package com.familyfinance.ai;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
@SpringBootTest(properties={"app.seed.enabled=true","app.ai.api-key=test-server-secret","app.ai.base-url=https://testworkspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"})
@AutoConfigureMockMvc @Transactional
class SystemAiSettingsApiTest {
 @Autowired MockMvc mvc;
 @MockitoBean AiTransport transport;
 @Test void directoryTestOnHttpRequiresConsentAndNeverSendsDocuments() throws Exception {
  var session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234"))
    .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  mvc.perform(post("/api/me/ai-settings/test").session(session).with(csrf()).contentType("application/json").content("{\"confirmed\":false}"))
    .andExpect(status().isBadRequest());
  org.mockito.Mockito.verifyNoInteractions(transport);
  org.mockito.Mockito.when(transport.exchange(java.net.URI.create("https://testworkspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/models"),"test-server-secret",null))
    .thenReturn("{\"data\":[{\"id\":\"qwen3.8-max\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  mvc.perform(post("/api/me/ai-settings/test").session(session).with(csrf()).contentType("application/json").content("{\"confirmed\":true}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.modelListed").value(true));
 }
 @Test void sharedStatusWorksOnHttpWithoutExposingCredentialsOrAllowingWrites() throws Exception {
  mvc.perform(get("/api/me/ai-settings")).andExpect(status().isUnauthorized());
  var session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234"))
    .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  mvc.perform(get("/api/me/ai-settings").session(session)).andExpect(status().isOk())
    .andExpect(jsonPath("$.data.model").value("qwen3.8-max")).andExpect(jsonPath("$.data.ready").value(true))
    .andExpect(jsonPath("$.data.apiKey").doesNotExist()).andExpect(jsonPath("$.data.baseUrl").doesNotExist());
  mvc.perform(put("/api/me/ai-settings").session(session).with(csrf()).contentType("application/json").content("{}"))
    .andExpect(status().isMethodNotAllowed());
  org.mockito.Mockito.verifyNoInteractions(transport);
 }
}
