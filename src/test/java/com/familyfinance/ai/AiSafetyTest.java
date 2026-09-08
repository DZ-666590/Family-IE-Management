package com.familyfinance.ai;

import static org.assertj.core.api.Assertions.*;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiSafetyTest {
    @Test void ciphertextIsRandomAuthenticatedAndUserBound() {
        var cipher = new AiSecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String a = cipher.encrypt(1, "test-secret"), b = cipher.encrypt(1, "test-secret");
        assertThat(a).isNotEqualTo(b).doesNotContain("test-secret");
        assertThat(cipher.decrypt(1, a)).isEqualTo("test-secret");
        assertThatThrownBy(() -> cipher.decrypt(2, a)).isInstanceOf(AiFailure.class);
        assertThatThrownBy(() -> cipher.decrypt(1, a.substring(0, a.length() - 5) + "AAAAA")).isInstanceOf(AiFailure.class);
    }
    @ParameterizedTest @ValueSource(strings = {"", "invalid", "YQ=="})
    void unavailableCipherFailsClosed(String key) {
        var cipher = new AiSecretCipher(key);
        assertThat(cipher.ready()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt(1, "test-secret")).isInstanceOf(AiFailure.class);
    }
    @ParameterizedTest @ValueSource(strings = {"http://api.example.com/v1", "https://localhost/v1", "https://127.0.0.1/v1",
            "https://api.example.com:444/v1", "https://user:secret@api.example.com/v1", "https://api.example.com/v1?key=secret",
            "https://api.example.com/v1#fragment", "https://api.example.com/a/../v1", "https://api.example.com/%2e%2e",
            "https://api.example.com.evil.org/v1", "https://api.example.com./v1", "https://2130706433/v1", "https://[::1]/v1"})
    void rejectsUnsafeUrls(String url) {
        assertThatThrownBy(() -> new AiEndpointPolicy("api.example.com").validate(url)).isInstanceOf(AiFailure.class);
    }
    @ParameterizedTest @ValueSource(strings = {"0.0.0.0", "10.1.1.1", "127.0.0.1", "169.254.169.254", "172.16.1.1", "192.168.1.1", "100.64.0.1",
            "198.18.0.1", "192.88.99.1", "3fff::1", "224.0.0.1", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::", "::ffff:127.0.0.1"})
    void rejectsNonPublicDnsAddresses(String address) throws Exception {
        assertThat(AiEndpointPolicy.isPublic(InetAddress.getByName(address))).isFalse();
    }
    @Test void permitsOnlyConfiguredHostsAndPublicAddresses() throws Exception {
        assertThat(new AiEndpointPolicy("api.example.com").validate("https://api.example.com:443/v1/").toString())
                .isEqualTo("https://api.example.com/v1");
        assertThat(AiEndpointPolicy.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(AiEndpointPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
    }
}
