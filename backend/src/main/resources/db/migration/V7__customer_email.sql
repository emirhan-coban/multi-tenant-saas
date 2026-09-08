alter table appointments add column customer_email varchar(254);
alter table appointments add column email_notifications boolean not null default false;
alter table appointments add constraint email_notification_recipient check (not email_notifications or (customer_email is not null and length(trim(customer_email)) > 0));
alter table outbox add column recipient_email varchar(254);
-- Existing console events remain console events; no historical customer email is generated.
