package com.saas.backend.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Faz 10 — telefondaki rezervasyon akışının saha ölçümü.
 *
 * <p>LCP ve INP tek başına 3B deneyimi anlatmaz: model inerken sayfa "yüklenmiş" görünür ama
 * kullanıcı henüz berber seçemez. Bu yüzden istemci, sahnenin <b>seçilebilir</b> hâle gelme
 * süresini (tts) ve liste yolunun aynı ölçüsünü ayrıca gönderir.</p>
 *
 * <p>Uç herkese açıktır (rezervasyon sayfası oturumsuz kullanılabilir) ama yalnızca sayı kabul
 * eder: kimlik, işletme, IP ya da serbest metin saklanmaz; gövde doğrulanmadan hiçbir şey
 * kaydedilmez ve yanıt her zaman 204'tür, böylece ölçüm gönderimi akışı hiç etkilemez.</p>
 */
@RestController
public class BookingVitalsController {
    /** Saçma büyüklükteki değerler histogramı bozmasın diye üst sınır: 2 dakika. */
    private static final long MAX_MS = 120_000;

    /** Açık uç: saniyede bu sayıdan fazla ölçüm kaydedilmez, kalanı sessizce atılır. */
    private static final int MAX_PER_SECOND = 20;

    private final OpsMetrics metrics;
    private final java.util.concurrent.atomic.AtomicLong window = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger inWindow = new java.util.concurrent.atomic.AtomicInteger();

    public BookingVitalsController(OpsMetrics metrics) {
        this.metrics = metrics;
    }

    public record Vitals(
            @Pattern(regexp = "3d|list") String view,
            @Pattern(regexp = "high|low|unknown") String quality,
            @Size(max = 16) String stage,
            @Min(0) @Max(MAX_MS) Long tts,
            @Min(0) @Max(MAX_MS) Long listReady,
            @Min(0) @Max(MAX_MS) Long lcp,
            @Min(0) @Max(MAX_MS) Long inp,
            @Min(0) @Max(5_000) Long frameMedian,
            @Min(0) @Max(100_000) Long longFrames,
            Boolean narrow
    ) {
    }

    @PostMapping("/api/public/v1/metrics/booking")
    public ResponseEntity<Void> record(@Valid @RequestBody Vitals body) {
        if (!accept()) return ResponseEntity.noContent().build();
        String view = body.view() == null ? "3d" : body.view();
        String quality = body.quality() == null ? "unknown" : body.quality();
        record("tts", view, quality, body.tts());
        record("list_ready", view, quality, body.listReady());
        record("lcp", view, quality, body.lcp());
        record("inp", view, quality, body.inp());
        record("frame_median", view, quality, body.frameMedian());
        record("long_frames", view, quality, body.longFrames());
        metrics.bookingStage(stage(body.stage()), view);
        return ResponseEntity.noContent().build();
    }

    /** Basit sabit pencere: kötüye kullanım istatistikleri bozamaz, ek altyapı gerekmez. */
    private boolean accept() {
        long second = System.currentTimeMillis() / 1000;
        if (window.getAndSet(second) != second) inWindow.set(0);
        return inWindow.incrementAndGet() <= MAX_PER_SECOND;
    }

    private void record(String metric, String view, String quality, Long value) {
        if (value != null) metrics.bookingVital(metric, view, quality, value);
    }

    /** Etiket kardinalitesi sabit kalsın diye bilinmeyen aşama adları "other" olur. */
    private static String stage(String stage) {
        return switch (stage == null ? "" : stage) {
            case "barber", "time", "details", "review" -> stage;
            default -> "other";
        };
    }
}
