package com.saas.backend.operations;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpTests {
    private static MockHttpServletRequest request(String peer, String forwarded, String headerName, String headerValue) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        if (headerName != null) request.addHeader(headerName, headerValue);
        return request;
    }

    @Test
    void ignoresForwardedHeaderByDefault() {
        var resolver = new ClientIp("", 0);
        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", "6.6.6.6, 7.7.7.7", null, null)));
    }

    @Test
    void usesNthEntryFromTheRightBehindTrustedProxies() {
        var resolver = new ClientIp("", 1);
        // Sahte sol girdi etkisizdir: yalnızca kendi proxy'mizin yazdığı son girdi alınır.
        assertEquals("203.0.113.5", resolver.resolve(request("10.0.0.9", "6.6.6.6, 203.0.113.5", null, null)));
        assertEquals("203.0.113.5", resolver.resolve(request("10.0.0.9", "203.0.113.5", null, null)));
    }

    @Test
    void fallsBackToPeerWhenChainIsShorterThanTrustedHops() {
        var resolver = new ClientIp("", 2);
        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", "203.0.113.5", null, null)));
        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", null, null, null)));
    }

    @Test
    void usesConfiguredGatewayHeaderAndRejectsGarbage() {
        var resolver = new ClientIp("CF-Connecting-IP", 0);
        assertEquals("198.51.100.7", resolver.resolve(request("10.0.0.9", "6.6.6.6", "CF-Connecting-IP", "198.51.100.7")));
        assertEquals("2001:db8::1", resolver.resolve(request("10.0.0.9", null, "CF-Connecting-IP", "2001:db8::1")));
        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", "6.6.6.6", "CF-Connecting-IP", "<script>")));
        assertEquals("10.0.0.9", resolver.resolve(request("10.0.0.9", "6.6.6.6", null, null)));
    }
}
