alter table users add column session_version bigint not null default 0;
alter table customer_accounts add column session_version bigint not null default 0;