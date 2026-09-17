import { useEffect, useRef, useState } from 'react';

/**
 * Faz 10 — dar ekran düzeni.
 *
 * Masaüstü düzeni (sahne solda, panel sağda) olduğu gibi kalır; aşamalı akış yalnızca
 * dar ekranda devreye girer. `matchMedia` yoksa (test ortamı, çok eski tarayıcı)
 * masaüstü düzeni kullanılır: davranış hiç değişmez.
 */
export function useCompactLayout(query = '(max-width: 840px)') {
  const [compact, setCompact] = useState(() => matches(query));
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const list = window.matchMedia(query);
    const update = () => setCompact(list.matches);
    update();
    list.addEventListener('change', update);
    return () => list.removeEventListener('change', update);
  }, [query]);
  return compact;
}

function matches(query: string) {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
}

/**
 * Telefon geri tuşu akışta bir aşama geri alır, dükkândan çıkarmaz.
 *
 * Her ileri adım bir history girdisi bırakır; `popstate` geldiğinde derinliğe göre geri
 * dönülür. En baştaki aşamada geri tuşu normal davranır (sayfadan çıkar), çünkü o noktada
 * yeni bir girdi bırakılmamıştır.
 */
export function useStageHistory(depth: number, onBack: (depth: number) => void) {
  const pushed = useRef(0);
  const handler = useRef(onBack);
  handler.current = onBack;

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const listener = (event: PopStateEvent) => {
      const target = typeof event.state?.bkDepth === 'number' ? event.state.bkDepth : 0;
      if (target < pushed.current) {
        pushed.current = target;
        handler.current(target);
      }
    };
    window.addEventListener('popstate', listener);
    return () => window.removeEventListener('popstate', listener);
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined' || depth === pushed.current) return;
    if (depth > pushed.current) window.history.pushState({ bkDepth: depth }, '');
    // Geri gidiş popstate ile yönetilir; burada yalnızca sayaç eşitlenir.
    pushed.current = depth;
  }, [depth]);
}

/**
 * Klavye açıldığında sabit alt çubuğun alan alanları örtmemesi için görünür alanın
 * ne kadar kısaldığını CSS değişkenine yazar.
 *
 * Modern tarayıcılarda `interactive-widget=resizes-content` zaten içeriği yukarı iter;
 * bu kanca onu desteklemeyen sürümler için yedektir ve iki durumda da doğru çalışır
 * (klavye yokken değer 0'dır).
 */
export function useKeyboardInset() {
  useEffect(() => {
    const viewport = typeof window !== 'undefined' ? window.visualViewport : undefined;
    if (!viewport) return;
    const update = () => {
      const inset = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop);
      document.documentElement.style.setProperty('--bk-keyboard', `${Math.round(inset)}px`);
    };
    update();
    viewport.addEventListener('resize', update);
    viewport.addEventListener('scroll', update);
    return () => {
      viewport.removeEventListener('resize', update);
      viewport.removeEventListener('scroll', update);
      document.documentElement.style.removeProperty('--bk-keyboard');
    };
  }, []);
}
