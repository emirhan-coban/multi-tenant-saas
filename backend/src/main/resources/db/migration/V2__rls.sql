alter table tenants enable row level security;

create policy tenant_isolation_select on tenants
  for select
                 using (id = current_setting('app.current_tenant', true)::uuid);