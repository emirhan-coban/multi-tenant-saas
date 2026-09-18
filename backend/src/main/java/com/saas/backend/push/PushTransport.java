package com.saas.backend.push;

import java.util.Map;

/** Push servisine tek bir HTTP isteği. Testlerde sahte uygulamayla değiştirilir. */
public interface PushTransport {
    /** @return HTTP durum kodu; ağ hatasında {@link java.io.IOException} fırlatır. */
    int send(String endpoint, Map<String, String> headers, byte[] body) throws java.io.IOException, InterruptedException;
}
