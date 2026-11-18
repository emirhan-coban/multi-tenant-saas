package com.saas.backend.operations;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * İstemci IP adresini güvenli çözer; hız sınırları bu adrese dayanır.
 *
 * <p>İstemcinin gönderdiği {@code X-Forwarded-For} başlığı <b>hiçbir zaman</b> olduğu gibi
 * güvenilmez (sahte başlıkla sınır atlatılabilirdi). Üç mod vardır:</p>
 * <ol>
 *   <li>{@code app.security.client-ip-header} tanımlıysa (örn. {@code CF-Connecting-IP}) o başlık kullanılır.
 *       Yalnızca başlığı kendisi yazan/üzerine yazan bir ağ geçidinin arkasında açılmalıdır.</li>
 *   <li>{@code app.security.trusted-proxy-hops} = N &gt; 0 ise {@code X-Forwarded-For} zincirinin
 *       <b>sağdan</b> N. girdisi alınır (kendi proxy'lerimizin eklediği son atlama).</li>
 *   <li>Aksi halde doğrudan TCP bağlantısının adresi ({@code getRemoteAddr}).</li>
 * </ol>
 */
@Component
public class ClientIp {
    private static final Logger LOG = LoggerFactory.getLogger(ClientIp.class);
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final String header;
    private final int hops;
    private volatile boolean warned;

    public ClientIp(@Value("${app.security.client-ip-header:}") String header,
                    @Value("${app.security.trusted-proxy-hops:0}") int hops) {
        this.header = header == null ? "" : header.trim();
        this.hops = Math.max(0, hops);
    }

    public String resolve(HttpServletRequest request) {
        if (!header.isEmpty()) {
            String value = first(request.getHeader(header));
            if (value != null) return value;
            warnOnce("İstemci IP başlığı (" + header + ") gelmedi; bağlantı adresi kullanılıyor. Proxy yapılandırmasını denetleyin.");
        } else if (hops > 0) {
            String chain = request.getHeader("X-Forwarded-For");
            if (chain != null && !chain.isBlank()) {
                String[] parts = chain.split(",");
                int index = parts.length - hops;
                if (index >= 0) {
                    String value = first(parts[index]);
                    if (value != null) return value;
                }
            }
            warnOnce("X-Forwarded-For zinciri beklenen uzunlukta değil; bağlantı adresi kullanılıyor.");
        }
        return request.getRemoteAddr();
    }

    private static String first(String raw) {
        if (raw == null) return null;
        String value = raw.split(",")[0].trim();
        return IP_LITERAL.matcher(value).matches() ? value : null;
    }

    private void warnOnce(String message) {
        if (!warned) { warned = true; LOG.warn(message); }
    }
}
