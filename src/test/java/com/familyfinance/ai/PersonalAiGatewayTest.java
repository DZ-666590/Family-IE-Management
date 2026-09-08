package com.familyfinance.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.MembershipContext;
import com.familyfinance.family.HouseholdRole;
import java.net.URI;
import java.net.InetAddress;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;

class PersonalAiGatewayTest {
    private final AiSettingsService settings = mock(AiSettingsService.class);
    private final AiTransport transport = mock(AiTransport.class);
    private final CurrentMembership membership = mock(CurrentMembership.class);
    private final Authentication auth = mock(Authentication.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PersonalAiGateway gateway = new PersonalAiGateway(settings, transport, membership, mapper, Clock.systemUTC());
    private void configured() {
        when(settings.credential(auth)).thenReturn(new AiSettingsService.Credential("https://api.example.com/v1", "sample", "test-secret"));
        when(membership.require(auth)).thenReturn(new MembershipContext(1L, 2L, HouseholdRole.MEMBER));
    }
    @Test void refusesUnapprovedInferenceBeforeReadingCredentialsOrSending() {
        assertThatThrownBy(() -> gateway.complete(auth, new AiGateway.Prompt("text", false))).isInstanceOf(AiFailure.class);
        verifyNoInteractions(settings, transport);
    }
    @Test void sendsOnlyExplicitTextWithBoundedNonStreamingOutput() {
        configured();
        when(transport.exchange(any(), any(), any())).thenAnswer(invocation -> {
            var node = mapper.readTree((byte[]) invocation.getArgument(2));
            assertThat(node.path("messages").size()).isEqualTo(1);
            assertThat(node.path("messages").path(0).path("content").asText()).isEqualTo("user-approved sample");
            assertThat(node.path("max_tokens").asInt()).isEqualTo(256);
            assertThat(node.path("stream").asBoolean()).isFalse();
            assertThat(node.has("tools")).isFalse();
            return "{\"choices\":[{\"message\":{\"content\":\"sample answer\"}}]}".getBytes(StandardCharsets.UTF_8);
        });
        assertThat(gateway.complete(auth, new AiGateway.Prompt("user-approved sample", true))).isEqualTo("sample answer");
        var body = ArgumentCaptor.forClass(byte[].class);
        // Capture during exchange because gateway clears the payload after completion.
        verify(transport).exchange(eq(URI.create("https://api.example.com/v1/chat/completions")), eq("test-secret"), body.capture());
        assertThat(body.getValue()).containsOnly((byte) 0);
    }
    @Test void neverReturnsAProviderEchoOfTheCredential() {
        configured();
        when(transport.exchange(any(), any(), any())).thenReturn("{\"choices\":[{\"message\":{\"content\":\"test-secret\"}}]}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> gateway.complete(auth, new AiGateway.Prompt("approved", true)))
                .isInstanceOf(AiFailure.class).hasMessageNotContaining("test-secret");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "null", "[]"})
    void emptyOrNullResponsesAreSafeProviderErrors(String response) {
        configured();
        when(transport.exchange(any(), any(), any())).thenReturn(response.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> gateway.test(auth, true)).isInstanceOf(AiFailure.class)
                .satisfies(error -> assertThat(((AiFailure) error).code()).isEqualTo("AI_INVALID_RESPONSE"));
    }
    @Test void rejectsMixedPublicAndPrivateDnsAnswersWithoutUsingEither() throws Exception {
        assertThatThrownBy(() -> SafeAiTransport.PublicDnsResolver.checked(new InetAddress[]{
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")}))
                .isInstanceOf(java.net.UnknownHostException.class);
    }
    @Test void invalidUpstreamJsonIsNeverExposed() {
        configured();
        when(transport.exchange(any(), any(), any())).thenReturn("test-secret malformed response".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> gateway.test(auth, true)).isInstanceOf(AiFailure.class).hasMessageNotContaining("test-secret");
    }
}
