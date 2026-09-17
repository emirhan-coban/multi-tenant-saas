import { useEffect, useRef } from 'react';
import type { PublicShop } from '../../api/customer-booking';
import { initials, type ChairStatus } from '../scene-helpers';

/**
 * Faz 10 — berber isimleri 3B sahneden çıkarılıp sahnenin altındaki şeride taşındı.
 *
 * Sahnede yalnızca koltuk numarası kalır (uzun isimler küçük ekranda birbirini örtüyordu).
 * Şerit ile sahne çift yönlü bağlıdır: koltuğa dokununca şerit seçili kişiye kayar,
 * şeride dokununca koltuk vurgulanır. Şerit aynı zamanda 3B yüklenmeden de çalışan,
 * ekran okuyucuya açık gerçek bir seçim aracıdır.
 */
export function BarberStrip({ staff, chairNumbers, statuses, selectedId, onSelect, modelReady }: {
  staff: PublicShop['staff'];
  chairNumbers: Record<string, string>;
  statuses: Record<string, ChairStatus>;
  selectedId: string;
  onSelect: (staffId: string) => void;
  modelReady: boolean;
}) {
  const list = useRef<HTMLUListElement>(null);

  // Koltuğa dokunulduğunda seçili kişi şeritte görünür hâle gelir.
  useEffect(() => {
    const selected = list.current?.querySelector<HTMLElement>('[data-selected="true"]');
    selected?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' });
  }, [selectedId]);

  return <nav className="bk-strip" aria-label="Berber seçimi">
    {!modelReady && <p className="bk-strip-hint">Dükkân yükleniyor — buradan da seçebilirsin.</p>}
    <ul ref={list}>
      {staff.map(person => {
        const status = statuses[person.id];
        const full = status?.state === 'full';
        return <li key={person.id}>
          <button
            type="button"
            data-selected={person.id === selectedId}
            aria-pressed={person.id === selectedId}
            className={`bk-strip-item${person.id === selectedId ? ' is-selected' : ''}${full ? ' is-full' : ''}`}
            onClick={() => onSelect(person.id)}
          >
            <span className="bk-strip-avatar" aria-hidden="true">{initials(person.displayName)}</span>
            <span className="bk-strip-copy">
              <strong>{person.displayName}</strong>
              <small>
                {chairNumbers[person.id] ? `Koltuk ${chairNumbers[person.id]} · ` : ''}
                {full ? 'Bugün dolu' : status?.firstSlot ? `İlk müsait ${status.firstSlot}` : 'Saatler yükleniyor'}
              </small>
            </span>
          </button>
        </li>;
      })}
    </ul>
  </nav>;
}
