#!/usr/bin/env bash
set -euo pipefail

: "${TARGET_URL:?TARGET_URL geri yüklenen veritabanı bağlantısı olmalı}"
LEDGER="${1:?TSV deletion ledger dosyası verilmeli}"
[[ -r "$LEDGER" ]] || { echo "Ledger okunamıyor: $LEDGER" >&2; exit 2; }

while IFS=$'\t' read -r subject_type subject_id; do
  [[ -z "$subject_type" || "$subject_type" == "#"* ]] && continue
  [[ "$subject_type" == "CUSTOMER" ]] || { echo "Desteklenmeyen subject_type: $subject_type" >&2; exit 2; }
  psql "$TARGET_URL" -v ON_ERROR_STOP=1 -v account="$subject_id" <<'SQL'
select anonymize_customer_appointments(:'account'::uuid);
update customer_accounts set
  email = 'silinmis+' || id || '@hesap.gecersiz',
  full_name = 'Silinmiş kullanıcı', phone = null, password_hash = md5(gen_random_uuid()::text),
  email_verified_at = null, active = false, anonymized_at = coalesce(anonymized_at, now()),
  session_version = session_version + 1
where id = :'account'::uuid;
insert into account_deletion_tombstones(subject_type, subject_id)
values ('CUSTOMER', :'account'::uuid) on conflict do nothing;
SQL
done < "$LEDGER"

echo "Customer deletion ledger yeniden uygulandı: $LEDGER"