package com.saas.backend.push;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Ağa çıkmadan push isteklerini kaydeder; uç nokta bazında dönecek HTTP durumu ayarlanabilir. */
public class RecordingPushTransport implements PushTransport {
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();

    @Override
    public int send(String endpoint, Map<String, String> headers, byte[] body) {
        sent.add(new Sent(endpoint, Map.copyOf(headers), body.clone()));
        return statuses.getOrDefault(endpoint, 201);
    }

    public void respond(String endpoint, int status) { statuses.put(endpoint, status); }
    public List<Sent> sent() { return List.copyOf(sent); }
    public List<Sent> sentTo(String endpoint) { return sent.stream().filter(item -> item.endpoint().equals(endpoint)).toList(); }
    public void clear() { sent.clear(); statuses.clear(); }

    public record Sent(String endpoint, Map<String, String> headers, byte[] body) {}

    @TestConfiguration
    public static class Config {
        @Bean @Primary
        public RecordingPushTransport recordingPushTransport() { return new RecordingPushTransport(); }
    }
}
