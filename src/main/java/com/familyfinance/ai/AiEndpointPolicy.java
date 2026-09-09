package com.familyfinance.ai;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AiEndpointPolicy {
    private final List<String> allowed;
    @org.springframework.beans.factory.annotation.Autowired
    AiEndpointPolicy(@Value("${app.ai.allowed-hosts:}") String hosts, @Value("${app.ai.base-url:}") String systemUrl) {
        this(hosts + "," + AiSystemConfiguration.officialHost(systemUrl));
    }
    AiEndpointPolicy(String hosts) {
        allowed = Arrays.stream(hosts.split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).distinct().sorted().toList();
    }
    List<String> allowedHosts() { return allowed; }
    URI validate(String input) {
        try {
            if (input == null || input.length() > 500) throw AiFailure.invalid();
            URI uri = URI.create(input);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !host.matches("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
                    || !allowed.contains(host.toLowerCase(Locale.ROOT))
                    || !path.matches("(?:/[A-Za-z0-9_-]+)*/?")) throw AiFailure.invalid();
            String normalized = "https://" + host.toLowerCase(Locale.ROOT) + path.replaceAll("/$", "");
            return URI.create(normalized);
        } catch (IllegalArgumentException e) { throw AiFailure.invalid(); }
    }
    /** Used by the transport's DNS resolver: these exact addresses are used for the socket. */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int a = b[0] & 255, c = b[1] & 255;
        if (b.length == 4) {
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 169 && c == 254) && !(a == 172 && c >= 16 && c <= 31)
                    && !(a == 192 && (c == 168 || c == 0 || c == 2))
                    && !(a == 192 && c == 88 && (b[2] & 255) == 99)
                    && !(a == 198 && (c == 18 || c == 19 || c == 51))
                    && !(a == 203 && c == 0 && (b[2] & 255) == 113);
        }
        // Only global unicast; exclude documentation, Teredo, 6to4 and special 2001::/23.
        return b.length == 16 && (a & 0xe0) == 0x20
                && !(a == 0x3f && c == 0xff && (b[2] & 0xf0) == 0)
                && !(a == 0x20 && c == 0x02)
                && !(a == 0x20 && c == 0x01 && ((b[2] & 255) < 2
                || ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)));
    }
}
