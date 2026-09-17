alter table staff add constraint uq_staff_id_tenant unique (id, tenant_id);

alter table shop_chairs
    add constraint fk_shop_chair_staff_tenant
    foreign key (staff_id, tenant_id) references staff(id, tenant_id);
