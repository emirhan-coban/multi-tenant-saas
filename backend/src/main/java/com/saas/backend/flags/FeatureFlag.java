package com.saas.backend.flags;

import java.util.Arrays;
import java.util.Optional;

/** Kontrollü yayın anahtarları. Anahtar listesi V17 migration'daki check kısıtıyla birebir aynıdır. */
public enum FeatureFlag {
    /** 3D koltuk seçimi. Kapalıyken sahne manifesti dönmez ve WEB_3D istekleri reddedilir; liste modu çalışır. */
    BOOKING_3D("booking.3d", true, true),
    /** Web push aboneliği ve gönderimi. Kapalıyken yeni teslimat kuyruğa alınmaz. */
    PUSH_NOTIFICATIONS("push.notifications", true, true),
    /** Ana ekrana ekleme davetinin gösterilmesi (yalnızca istemci). */
    PWA_INSTALL("pwa.install", true, false),
    /** Güvenilir müşteriler için otomatik onay. İşletme ayarına ek bir global acil durum anahtarıdır. */
    TRUSTED_AUTO_CONFIRM("booking.trusted_auto_confirm", true, true);

    private final String key;
    private final boolean defaultValue;
    private final boolean tenantOverridable;

    FeatureFlag(String key, boolean defaultValue, boolean tenantOverridable) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.tenantOverridable = tenantOverridable;
    }

    public String key() { return key; }
    public boolean defaultValue() { return defaultValue; }
    public boolean tenantOverridable() { return tenantOverridable; }

    public static Optional<FeatureFlag> fromKey(String key) {
        return Arrays.stream(values()).filter(flag -> flag.key.equals(key)).findFirst();
    }
}
