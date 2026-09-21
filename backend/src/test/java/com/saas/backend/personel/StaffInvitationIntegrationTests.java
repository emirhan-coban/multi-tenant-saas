package com.saas.backend.personel;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.account.AccountMailer;
import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class StaffInvitationIntegrationTests extends PostgresTestBase {
    @Autowired PersonelService service;
    @MockitoBean AccountMailer mailer;
    final JdbcTemplate db=ownerJdbc();
    UUID tenant, ownerId;
    AuthenticatedUser owner;

    @BeforeEach void setup() {
        tenant=UUID.randomUUID(); ownerId=UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,'Europe/Istanbul')",tenant,"Davet Test",tenant.toString());
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)",ownerId,ownerId+"@example.test","unused","Owner");
        db.update("insert into memberships(user_id,tenant_id,role) values (?,?,'OWNER')",ownerId,tenant);
        owner=new AuthenticatedUser(ownerId,tenant,"OWNER");
        when(mailer.staffInvitation(anyString(),anyString(),anyString())).thenReturn(true);
    }

    @Test void invitationIsInactiveOneTimeAndAccessCanBeRevoked() {
        var result=service.davetEt(owner,new PersonelDtos.DavetEtRequest("new.staff@example.test","Yeni Personel"));
        assertTrue(result.invitationSent());
        assertFalse(db.queryForObject("select active from staff where id=?",Boolean.class,result.personelId()));

        var token=ArgumentCaptor.forClass(String.class);
        verify(mailer).staffInvitation(eq("new.staff@example.test"),token.capture(),eq("Davet Test"));
        var weak=assertThrows(ApiException.class,()->service.davetiKabulEt(new PersonelDtos.DavetKabulRequest(token.getValue(),"weakpass")));
        assertEquals("WEAK_PASSWORD",weak.getCode());

        service.davetiKabulEt(new PersonelDtos.DavetKabulRequest(token.getValue(),"StrongPass1"));
        assertTrue(db.queryForObject("select active from staff where id=?",Boolean.class,result.personelId()));
        assertTrue(db.queryForObject("select m.active from memberships m join staff s on s.user_id=m.user_id and s.tenant_id=m.tenant_id where s.id=?",Boolean.class,result.personelId()));
        var replay=assertThrows(ApiException.class,()->service.davetiKabulEt(new PersonelDtos.DavetKabulRequest(token.getValue(),"StrongPass1")));
        assertEquals("INVALID_ACCOUNT_TOKEN",replay.getCode());

        service.erisimiKaldir(owner,result.personelId());
        assertFalse(db.queryForObject("select active from staff where id=?",Boolean.class,result.personelId()));
        assertFalse(db.queryForObject("select m.active from memberships m join staff s on s.user_id=m.user_id and s.tenant_id=m.tenant_id where s.id=?",Boolean.class,result.personelId()));
    }
}
