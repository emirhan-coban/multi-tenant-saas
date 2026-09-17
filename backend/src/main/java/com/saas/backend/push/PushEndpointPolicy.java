package com.saas.backend.push;

import com.saas.backend.auth.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Sunucu, abonelik uç noktasına kendisi HTTP isteği attığı için bu doğrulama SSRF korumasıdır:
 * yalnızca bilinen tarayıcı push servislerine, HTTPS ve varsayılan port üzerinden gidilir.
 */
@Component
public class PushEndpointPolicy {
    private final List<String> allowedHostSuffixes;

    public PushEndpointPolicy(@Value("${app.push.allowed-hosts:fcm.googleapis.com,updates.push.services.mozilla.com,push.services.mozilla.com,notify.windows.com,push.apple.com}") String hosts) {
        this.allowedHostSuffixes = Arrays.stream(hosts.split(",")).map(String::trim).filter(h -> !h.isEmpty())
                .map(h -> h.toLowerCase(Locale.ROOT)).toList();
    }

    public URI validate(String endpoint) {
        if (endpoint == null || endpoint.length() > 1024) throw invalid();
        final URI uri;
        try { uri = URI.create(endpoint.trim()); }
        catch (IllegalArgumentException ex) { throw invalid(); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null) throw invalid();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedHostSuffixes.stream().anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        if (!allowed) throw new ApiException("PUSH_ENDPOINT_NOT_ALLOWED", "Bu tarayıcının bildirim servisi desteklenmiyor.");
        return uri;
    }

    private static ApiException invalid() {
        return new ApiException("INVALID_PUSH_SUBSCRIPTION", "Bildirim aboneliği geçersiz.");
    }
}
