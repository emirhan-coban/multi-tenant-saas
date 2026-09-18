import { useRef, useState, type ReactNode } from 'react';

export type SheetState = 'peek' | 'expanded';

/**
 * Faz 10 — dar ekranda alt panel.
 *
 * Panel sürüklenebilir, ama sürükleme tek yol değildir: başlıkta açık bir genişlet/küçült
 * düğmesi vardır (sürükleme dokunmatik olmayan girdilerde ve yardımcı teknolojilerde
 * güvenilir değildir). Panel kipli (modal) değildir — küçükken sahne hâlâ kullanılabilir —
 * bu yüzden odak hapsi yoktur; bölge olarak işaretlenir.
 *
 * Alt çubuk (fiyat + birincil düğme) ekranın altında sabit kalır ve klavye açıldığında
 * `--bk-keyboard` kadar yukarı taşınır, böylece hiçbir alanı örtmez.
 */
export function BookingSheet({ state, onStateChange, title, subtitle, children, footer, labelledBy }: {
  state: SheetState;
  onStateChange: (next: SheetState) => void;
  title: ReactNode;
  subtitle?: ReactNode;
  children: ReactNode;
  footer: ReactNode;
  labelledBy?: string;
}) {
  const [drag, setDrag] = useState(0);
  const start = useRef<number | null>(null);

  function pointerDown(event: React.PointerEvent) {
    // Yalnızca tutamaçtan sürüklenir; içerik kaydırma jesti bozulmaz.
    start.current = event.clientY;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }

  function pointerMove(event: React.PointerEvent) {
    if (start.current === null) return;
    const delta = event.clientY - start.current;
    // Açıkken aşağı, kapalıyken yukarı sürüklenebilir; ters yön direnç gösterir.
    setDrag(state === 'expanded' ? Math.max(0, delta) : Math.min(0, delta));
  }

  function pointerUp() {
    if (start.current === null) return;
    start.current = null;
    if (drag > 60) onStateChange('peek');
    else if (drag < -60) onStateChange('expanded');
    setDrag(0);
  }

  const expanded = state === 'expanded';
  return <section
    className={`bk-sheet bk-sheet-${state}`}
    style={drag ? { transform: `translateY(${drag}px)`, transition: 'none' } : undefined}
    aria-labelledby={labelledBy}
    role="region"
  >
    <div
      className="bk-sheet-grip"
      onPointerDown={pointerDown}
      onPointerMove={pointerMove}
      onPointerUp={pointerUp}
      onPointerCancel={pointerUp}
    >
      <span className="bk-sheet-handle" aria-hidden="true" />
    </div>
    <header className="bk-sheet-head">
      <div className="bk-sheet-title">{title}{subtitle && <span>{subtitle}</span>}</div>
      <button
        type="button"
        className="bk-sheet-toggle"
        aria-expanded={expanded}
        onClick={() => onStateChange(expanded ? 'peek' : 'expanded')}
      >
        <span className="bk-visually-hidden">{expanded ? 'Paneli küçült' : 'Paneli genişlet'}</span>
        <svg viewBox="0 0 24 24" aria-hidden="true" className={expanded ? 'is-down' : ''}>
          <path d="m6 15 6-6 6 6" />
        </svg>
      </button>
    </header>
    <div className="bk-sheet-body">{children}</div>
    <div className="bk-sheet-footer">{footer}</div>
  </section>;
}
