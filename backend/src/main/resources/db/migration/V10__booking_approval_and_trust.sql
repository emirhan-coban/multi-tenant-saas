alter table booking_requests add column decision_key uuid;
alter table booking_requests add column decision_action varchar(16)
    check (decision_action in ('APPROVE', 'REJECT'));
alter table booking_requests add column rejection_reason varchar(250);

alter table booking_requests add constraint booking_request_staff_decision_fields check (
    status not in ('APPROVED', 'REJECTED') or
    (decision_key is not null and decision_action is not null and decided_by is not null and decided_at is not null)
);
alter table booking_requests add constraint booking_request_rejection_fields check (
    (status = 'REJECTED') = (decision_action = 'REJECT') and
    (rejection_reason is null or status = 'REJECTED')
);

create index idx_booking_requests_pending_hold
    on booking_requests(tenant_id, hold_expires_at, created_at)
    where status = 'PENDING_APPROVAL';
