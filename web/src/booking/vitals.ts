/**
 * Faz 10 — rezervasyon ekranının saha ölçümü.
 *
 * LCP ve INP tek başına 3B deneyimi anlatmaz: model inerken sayfa "yüklenmiş" görünür
 * ama kullanıcı henüz berber seçemez. Bu yüzden iki ek ölçü tutulur:
 *
 * - **tts**: sayfanın açılmasından, 3B sahnenin seçilebilir hâle gelmesine kadar geçen süre.
 * - **listReady**: aynı ölçünün liste yolu için karşılığı — model inerken seçimin
 *   kullanılabilir kaldığını kanıtlar.
 *
 * Ayrıca ilk etkileşim saniyelerindeki kare bütçesi (medyan kare süresi ve 50 ms'yi aşan
 * kare sayısı) toplanır. Gönderilen gövde kişisel veri içermez: yalnızca süreler,
 * cihaz sınıfı ve seçilen görünüm.
 */
export type BookingVitals = {
  view: '3d' | 'list';
  quality: 'high' | 'low' | 'unknown';
  tts: number | null;
  listReady: number | null;
  lcp: number | null;
  inp: number | null;
  frameMedian: number | null;
  longFrames: number | null;
  stage: string;
  narrow: boolean;
};

const ENDPOINT = '/api/public/v1/metrics/booking';

function now() {
  return typeof performance !== 'undefined' ? Math.round(performance.now()) : 0;
}

export class BookingVitalsRecorder {
  private data: BookingVitals = {
    view: '3d', quality: 'unknown', tts: null, listReady: null, lcp: null, inp: null,
    frameMedian: null, longFrames: null, stage: 'barber', narrow: false,
  };
  private observers: PerformanceObserver[] = [];
  private frames: number[] = [];
  private sampling = false;
  private sent = false;
  private detach: (() => void) | null = null;

  start(narrow: boolean) {
    this.data.narrow = narrow;
    this.observe('largest-contentful-paint', entries => {
      const last = entries[entries.length - 1];
      if (last) this.data.lcp = Math.round(last.startTime);
    });
    // INP'nin tam tanımı yüzdelik gerektirir; burada en kötü etkileşim gecikmesi tutulur:
    // bütçeyi aşan bir etkileşim varsa bu değer onu yakalar.
    this.observe('event', entries => {
      for (const entry of entries) {
        const duration = Math.round(entry.duration);
        if (this.data.inp === null || duration > this.data.inp) this.data.inp = duration;
      }
    }, { durationThreshold: 40 });

    const flush = () => { if (document.visibilityState === 'hidden') this.send(); };
    document.addEventListener('visibilitychange', flush);
    window.addEventListener('pagehide', flush);
    this.detach = () => {
      document.removeEventListener('visibilitychange', flush);
      window.removeEventListener('pagehide', flush);
    };
  }

  /** 3B sahne seçilebilir hâle geldi. */
  sceneSelectable(quality: 'high' | 'low' | 'unknown') {
    if (this.data.tts !== null) return;
    this.data.tts = now();
    this.data.quality = quality;
    this.sampleFrames();
  }

  /** Liste/şerit ile seçim yapılabilir hâle geldi (model beklemeden). */
  listSelectable() {
    if (this.data.listReady === null) this.data.listReady = now();
  }

  view(view: '3d' | 'list') {
    this.data.view = view;
  }

  stage(stage: string) {
    this.data.stage = stage;
  }

  /** Sayfa kapanmasını beklemeden gönderir (randevu tamamlandığında çağrılır). */
  send() {
    if (this.sent) return;
    this.sent = true;
    this.summariseFrames();
    this.stop();
    // Yalnızca sendBeacon: sayfa kapanırken güvenilir olan tek yol budur ve
    // `fetch` yedeği, göreli adresi çözemeyen ortamlarda (test, worker) yakalanamayan
    // reddedilmiş bir söz bırakıyordu. Beacon yoksa ölçüm sessizce atlanır.
    try {
      if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
        navigator.sendBeacon(ENDPOINT, new Blob([JSON.stringify(this.data)], { type: 'application/json' }));
      }
    } catch {
      // Ölçüm gönderilemezse akış etkilenmez.
    }
  }

  stop() {
    this.sampling = false;
    this.observers.forEach(observer => observer.disconnect());
    this.observers = [];
    this.detach?.();
    this.detach = null;
  }

  snapshot(): BookingVitals {
    this.summariseFrames();
    return { ...this.data };
  }

  private observe(type: string, handler: (entries: PerformanceEntry[]) => void, options: Record<string, unknown> = {}) {
    if (typeof PerformanceObserver !== 'function') return;
    try {
      const observer = new PerformanceObserver(list => handler(list.getEntries()));
      observer.observe({ type, buffered: true, ...options } as PerformanceObserverInit);
      this.observers.push(observer);
    } catch {
      // Tarayıcı bu ölçüyü desteklemiyor; diğerleri toplanmaya devam eder.
    }
  }

  /** Sahne seçilebilir olduktan sonraki ilk 5 saniyede kare sürelerini toplar. */
  private sampleFrames() {
    if (this.sampling || typeof requestAnimationFrame !== 'function') return;
    this.sampling = true;
    const deadline = now() + 5000;
    let previous = now();
    const tick = () => {
      if (!this.sampling) return;
      const current = now();
      this.frames.push(current - previous);
      previous = current;
      if (current < deadline) requestAnimationFrame(tick);
      else this.sampling = false;
    };
    requestAnimationFrame(tick);
  }

  private summariseFrames() {
    if (this.frames.length < 10) return;
    const sorted = [...this.frames].sort((a, b) => a - b);
    this.data.frameMedian = sorted[Math.floor(sorted.length / 2)];
    this.data.longFrames = sorted.filter(value => value > 50).length;
  }
}
