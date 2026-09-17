package com.saas.backend.ops;

import com.saas.backend.PostgresTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Faz 10 — rezervasyon akışının saha ölçümü uç noktası.
 *
 * <p>Uç herkese açıktır; bu yüzden sınanan asıl şey, yalnızca doğrulanmış sayıların
 * kaydedilmesi ve etiket kümesinin sabit kalmasıdır.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingVitalsIntegrationTests extends PostgresTestBase {

    @Autowired MeterRegistry registry;
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void validMeasurementIsRecordedWithoutASession() {
        var response = post("""
                {"view":"3d","quality":"low","stage":"time","tts":1840,"listReady":420,
                 "lcp":1600,"inp":120,"frameMedian":18,"longFrames":2,"narrow":true}
                """);
        assertEquals(204, response.statusCode(), response.body());

        var summary = registry.find("saas.booking.vitals").tags("metric", "tts", "view", "3d", "quality", "low").summary();
        assertNotNull(summary, "tts ölçümü kaydedilmeli");
        assertTrue(summary.count() >= 1);
        assertTrue(summary.max() >= 1840);
        assertNotNull(registry.find("saas.booking.stage_reached").tags("stage", "time", "view", "3d").counter());
    }

    @Test
    void unknownStageIsFoldedSoLabelCardinalityStaysFixed() {
        assertEquals(204, post("{\"view\":\"list\",\"quality\":\"unknown\",\"stage\":\"kendi-asamam\",\"tts\":900}").statusCode());
        assertNotNull(registry.find("saas.booking.stage_reached").tags("stage", "other", "view", "list").counter());
        assertNull(registry.find("saas.booking.stage_reached").tags("stage", "kendi-asamam").counter());
    }

    @Test
    void rubbishValuesAreRejectedBeforeAnythingIsRecorded() {
        assertEquals(400, post("{\"view\":\"hologram\",\"quality\":\"low\"}").statusCode(), "Bilinmeyen görünüm reddedilir");
        assertEquals(400, post("{\"view\":\"3d\",\"quality\":\"olagan\"}").statusCode(), "Bilinmeyen kalite reddedilir");
        assertEquals(400, post("{\"view\":\"3d\",\"quality\":\"low\",\"tts\":999999999}").statusCode(), "Akıl dışı süre reddedilir");
        assertEquals(400, post("{\"view\":\"3d\",\"quality\":\"low\",\"tts\":-5}").statusCode(), "Negatif süre reddedilir");
    }

    @Test
    void missingMeasurementsAreSimplySkipped() {
        var before = count("lcp");
        assertEquals(204, post("{\"view\":\"3d\",\"quality\":\"high\",\"stage\":\"barber\",\"tts\":700}").statusCode());
        assertEquals(before, count("lcp"), "Gönderilmeyen ölçü uydurulmaz");
    }

    private long count(String metric) {
        var summary = registry.find("saas.booking.vitals").tags("metric", metric, "view", "3d", "quality", "high").summary();
        return summary == null ? 0 : summary.count();
    }

    private HttpResponse<String> post(String body) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public/v1/metrics/booking"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
