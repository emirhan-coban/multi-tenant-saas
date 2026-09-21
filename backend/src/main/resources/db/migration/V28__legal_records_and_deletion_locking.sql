-- Aydınlatma, sözleşme kabulü ve isteğe bağlı izinler birbirinden ayrı kanıtlanır.
create table legal_documents (
    code varchar(40) not null,
    version varchar(32) not null,
    title varchar(160) not null,
    published_at timestamptz not null default now(),
    content_sha256 varchar(64),
    primary key (code, version)
);

create table legal_acceptances (
    id uuid primary key default gen_random_uuid(),
    subject_type varchar(16) not null check (subject_type in ('STAFF', 'CUSTOMER')),
    subject_id uuid not null,
    document_code varchar(40) not null,
    document_version varchar(32) not null,
    acceptance_type varchar(24) not null check (acceptance_type in ('ACKNOWLEDGEMENT', 'CONTRACT')),
    source varchar(32) not null default 'REGISTRATION',
    accepted_at timestamptz not null default now(),
    unique (subject_type, subject_id, document_code, document_version),
    foreign key (document_code, document_version) references legal_documents(code, version)
);

create table purpose_consents (
    id uuid primary key default gen_random_uuid(),
    subject_type varchar(16) not null check (subject_type in ('STAFF', 'CUSTOMER')),
    subject_id uuid not null,
    purpose varchar(40) not null,
    granted boolean not null,
    document_version varchar(32),
    recorded_at timestamptz not null default now()
);

create index idx_legal_acceptances_subject on legal_acceptances(subject_type, subject_id, accepted_at desc);
create index idx_purpose_consents_subject on purpose_consents(subject_type, subject_id, purpose, recorded_at desc);

insert into legal_documents(code, version, title)
values ('PRIVACY_NOTICE', '2026-09', 'Kişisel Verilerin Korunması Aydınlatma Metni'),
       ('TERMS_OF_SERVICE', '2026-09', 'Hizmet Kullanım Koşulları')
on conflict do nothing;

insert into legal_acceptances(subject_type, subject_id, document_code, document_version, acceptance_type, accepted_at)
select 'STAFF', id, 'PRIVACY_NOTICE', consent_version, 'ACKNOWLEDGEMENT', consent_at
from users where consent_at is not null and consent_version is not null
on conflict do nothing;

insert into legal_acceptances(subject_type, subject_id, document_code, document_version, acceptance_type, accepted_at)
select 'CUSTOMER', id, 'PRIVACY_NOTICE', consent_version, 'ACKNOWLEDGEMENT', consent_at
from customer_accounts where consent_at is not null and consent_version is not null
on conflict do nothing;
