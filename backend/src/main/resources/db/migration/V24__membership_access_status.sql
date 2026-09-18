alter table memberships add column active boolean not null default true;
create index idx_memberships_user_active on memberships(user_id, active);