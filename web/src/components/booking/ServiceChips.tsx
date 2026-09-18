import type { PublicShop } from '../../api/customer-booking';

/**
 * Faz 10 — hizmet seçimi sahnenin üstünde.
 *
 * Hizmet süresi müsaitliği belirlediği için koltuk etiketlerindeki "ilk müsait" saatinin
 * doğru olması hizmetin önce seçilmiş olmasına bağlıdır. Çipler varsayılan bir hizmetle
 * açılır: kullanıcı hiç dokunmadan da doğru saatleri görür. Tek hizmetli dükkânda
 * seçilecek bir şey olmadığı için şerit hiç çizilmez.
 */
export function ServiceChips({ services, value, onChange }: {
  services: PublicShop['services'];
  value: string;
  onChange: (id: string) => void;
}) {
  if (services.length < 2) return null;
  return <div className="bk-chips" role="radiogroup" aria-label="Hizmet">
    {services.map(service => <button
      key={service.id}
      type="button"
      role="radio"
      aria-checked={service.id === value}
      className={`bk-chip${service.id === value ? ' is-selected' : ''}`}
      onClick={() => onChange(service.id)}
    >
      <strong>{service.name}</strong>
      <small>{service.durationMin} dk</small>
    </button>)}
  </div>;
}
