-- appointments.customer_id ile tenant_customers.evidence_appointment_id birbirine
-- referans verebilir. Her iki satirin ayni transaction'da guvenle olusturulabilmesi ve
-- mantiksal yedeklerin atomik geri yuklenebilmesi icin kontrol commit'te yapilir.
alter table appointments
    alter constraint appointments_tenant_customer_fk
    deferrable initially deferred;

alter table tenant_customers
    alter constraint tenant_customer_evidence_appointment_fk
    deferrable initially deferred;
