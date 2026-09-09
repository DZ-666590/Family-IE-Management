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
    private final AiSystemConfiguration settings = mock(AiSystemConfiguration.class);
    private final AiTransport transport = mock(AiTransport.class);
    private final CurrentMembership membership = mock(CurrentMembership.class);
    private final Authentication auth = mock(Authentication.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PersonalAiGateway gateway = new PersonalAiGateway(settings, transport, membership, mapper, Clock.systemUTC());
    private void configured() {
        when(settings.credential()).thenReturn(new AiSystemConfiguration.Credential("https://api.example.com/v1", "sample", "test-secret"));
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
            assertThat(node.path("max_tokens").asInt()).isEqualTo(4096);
            assertThat(node.path("stream").asBoolean()).isFalse();
            assertThat(node.has("tools")).isFalse();
            return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"sample answer\"}}]}".getBytes(StandardCharsets.UTF_8);
        });
        assertThat(gateway.complete(auth, new AiGateway.Prompt("user-approved sample", true))).isEqualTo("sample answer");
        var body = ArgumentCaptor.forClass(byte[].class);
        // Capture during exchange because gateway clears the payload after completion.
        verify(transport).exchange(eq(URI.create("https://api.example.com/v1/chat/completions")), eq("test-secret"), body.capture());
        assertThat(body.getValue()).containsOnly((byte) 0);
    }
    @Test void neverReturnsAProviderEchoOfTheCredential() {
        configured();
        when(transport.exchange(any(), any(), any())).thenReturn("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"test-secret\"}}]}".getBytes(StandardCharsets.UTF_8));
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
    @Test void sendsValidatedImagesInlineWithoutRemoteImageUrls() throws Exception {
        configured();
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", bytes);
        when(transport.exchange(any(), any(), any())).thenAnswer(call -> {
            var root = mapper.readTree((byte[]) call.getArgument(2));
            var parts = root.path("messages").path(0).path("content");
            assertThat(parts.size()).isEqualTo(2);
            assertThat(parts.path(1).path("image_url").path("url").asText()).startsWith("data:image/png;base64,");
            assertThat(root.path("enable_thinking").asBoolean()).isFalse();
            return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"sample\"}}]}".getBytes(StandardCharsets.UTF_8);
        });
        assertThat(gateway.complete(auth, new AiGateway.Prompt("read sample", true,
                java.util.List.of(new AiGateway.DocumentImage("image/png", bytes.toByteArray()))))).isEqualTo("sample");
    }
    @Test void rejectsFakeImagesAndTruncatedAnswers() {
        configured();
        assertThatThrownBy(() -> gateway.complete(auth, new AiGateway.Prompt("read", true,
                java.util.List.of(new AiGateway.DocumentImage("image/png", new byte[]{1,2,3})))))
                .isInstanceOf(AiFailure.class);
        verifyNoInteractions(transport);
        when(transport.exchange(any(), any(), any())).thenReturn("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"incomplete\"}}]}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> gateway.complete(auth, new AiGateway.Prompt("read", true)))
                .isInstanceOf(AiFailure.class).satisfies(e -> assertThat(((AiFailure)e).code()).isEqualTo("AI_INCOMPLETE_RESPONSE"));
    }
    @Test void enforcesDailyUserQuotaBeforeSendingAndResetsOnNextDay() {
        configured();
        var now = new java.util.concurrent.atomic.AtomicLong(java.time.Instant.parse("2026-09-09T00:00:00Z").toEpochMilli());
        var testClock = mock(Clock.class);
        when(testClock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
        when(testClock.instant()).thenAnswer(x -> java.time.Instant.ofEpochMilli(now.get()));
        when(testClock.millis()).thenAnswer(x -> now.get());
        var limited = new PersonalAiGateway(settings, transport, membership, mapper, testClock);
        when(transport.exchange(any(), any(), any())).thenReturn("{\"data\":[]}".getBytes(StandardCharsets.UTF_8));
        for (int i=0; i<20; i++) { limited.test(auth,true); now.addAndGet(31_000); }
        assertThatThrownBy(() -> limited.test(auth,true)).isInstanceOf(AiFailure.class)
                .satisfies(e -> assertThat(((AiFailure)e).code()).isEqualTo("AI_DAILY_LIMIT"));
        verify(transport, times(20)).exchange(any(),any(),any());
        now.addAndGet(86_400_000); limited.test(auth,true);
        verify(transport, times(21)).exchange(any(),any(),any());
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
