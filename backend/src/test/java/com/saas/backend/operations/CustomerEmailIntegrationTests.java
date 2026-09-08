package com.saas.backend.operations;
import com.saas.backend.PostgresTestBase;
import com.saas.backend.appointment.*;
import com.saas.backend.config.AuthenticatedUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerEmailIntegrationTests extends PostgresTestBase {
    @Autowired AppointmentService appointments;
    @Autowired OutboxWorker worker;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoBean GmailSender gmail;
    UUID tenant,user,staff,service;
    AuthenticatedUser owner;
    Instant start=Instant.parse("2030-01-07T06:00:00Z");
    @BeforeEach void setup() {
        tenant=UUID.randomUUID();user=UUID.randomUUID();staff=UUID.randomUUID();service=UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,'Europe/Istanbul')",tenant,"Test İşletme",tenant.toString());
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)",user,user+"@example.test","unused","Owner");
        db.update("insert into staff(id,tenant_id,user_id,display_name) values (?,?,?,?)",staff,tenant,user,"Staff");
        db.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,30,10,120)",service,tenant,"Kesim");
        db.update("insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (?,?,0,'09:00','18:00')",tenant,staff);
        owner=new AuthenticatedUser(user,tenant,"OWNER");
        when(gmail.ready()).thenReturn(true);when(gmail.allowed(anyString())).thenReturn(true);
    }
    AppointmentDtos.Response create(boolean enabled,String email) {
        return json.readValue(appointments.create(owner,"mail",new AppointmentDtos.Create(staff,service,start,"Ada",null,"private note",email,enabled)),AppointmentDtos.Response.class);
    }
    int count(String status) {return db.queryForObject("select count(*) from outbox where tenant_id=? and event_type like 'CUSTOMER_%' and status=?",Integer.class,tenant,status);}
    @Test void optInCreatesConfirmationAndFutureReminderWithCustomerAddress() {
        var a=create(true,"customer@example.test");assertTrue(a.emailNotifications());assertEquals("customer@example.test",a.customerEmail());
        assertEquals(2,count("PENDING"));
        assertEquals(2,db.queryForObject("select count(*) from outbox where tenant_id=? and recipient_email='customer@example.test'",Integer.class,tenant));
        worker.process(tenant);
        verify(gmail).send(any(),eq("customer@example.test"),eq("Randevunuz onaylandı"),contains("07.01.2030 09:00"));
        verify(gmail).send(any(),anyString(),anyString(),argThat(body->!body.contains("private note")));
        assertEquals(1,count("SENT"));assertEquals(1,count("PENDING"));
    }
    @Test void optOutDoesNotQueueCustomerMail() {create(false,"customer@example.test");assertEquals(0,count("PENDING"));worker.process(tenant);verifyNoInteractions(gmail);}
    @Test void recipientRequiredForOptIn() {assertThrows(com.saas.backend.auth.ApiException.class,()->create(true,null));assertEquals(0,count("PENDING"));}
    @Test void replayDoesNotQueueDuplicates() {create(true,"customer@example.test");create(true,"customer@example.test");assertEquals(2,count("PENDING"));}
    @Test void disabledDeliveryDefersWithoutConsumingAttempts() {
        create(true,"customer@example.test");when(gmail.ready()).thenReturn(false);worker.process(tenant);
        assertEquals(2,count("PENDING"));assertEquals(0,db.queryForObject("select sum(attempts) from outbox where tenant_id=? and event_type like 'CUSTOMER_%'",Integer.class,tenant));
        verify(gmail,never()).send(any(),anyString(),anyString(),anyString());
    }
    @Test void testModeRejectsUnapprovedRecipient() {
        create(true,"customer@example.test");when(gmail.allowed(anyString())).thenReturn(false);worker.process(tenant);
        assertEquals(1,count("SKIPPED"));verify(gmail,never()).send(any(),anyString(),anyString(),anyString());
    }
    @Test void cancelSkipsOldMessagesAndSendsCancellation() {
        var a=create(true,"customer@example.test");appointments.patch(owner,a.id(),new AppointmentDtos.Patch(a.version(),null,Appointment.Status.CANCELLED,null));
        worker.process(tenant);assertEquals(2,count("SKIPPED"));assertEquals(1,count("SENT"));
        verify(gmail).send(any(),eq("customer@example.test"),eq("Randevunuz iptal edildi"),anyString());
    }
    @Test void movingReplacesPendingMessagesWithNewTime() {
        var a=create(true,"customer@example.test");appointments.patch(owner,a.id(),new AppointmentDtos.Patch(a.version(),start.plusSeconds(2400),null,null));
        worker.process(tenant);assertEquals(2,count("SKIPPED"));
        verify(gmail).send(any(),anyString(),eq("Randevu saatiniz değiştirildi"),contains("09:40"));
    }
    @Test void reminderIsDeliveredOnlyWhenDueAndOnlyOnce() {
        create(true,"customer@example.test");worker.process(tenant);worker.process(tenant);
        verify(gmail,times(1)).send(any(),anyString(),anyString(),anyString());
        db.update("update outbox set available_at=now() where tenant_id=? and event_type='CUSTOMER_REMINDER'",tenant);
        worker.process(tenant);worker.process(tenant);
        verify(gmail,times(1)).send(any(),anyString(),eq("Randevu hatırlatması"),anyString());
    }
    @Test void mailFailureRetriesAndEndsFailed() {
        create(true,"customer@example.test");
        doThrow(new IllegalStateException("SMTP unavailable")).when(gmail).send(any(),anyString(),anyString(),anyString());
        for(int i=0;i<5;i++) {
            db.update("update outbox set available_at=now() where tenant_id=? and event_type='CUSTOMER_CONFIRMATION'",tenant);
            worker.process(tenant);
        }
        assertEquals(1,count("FAILED"));verify(gmail,times(5)).send(any(),anyString(),anyString(),anyString());
    }
    @Test void completedAppointmentSkipsUnsentConfirmationAndReminder() {
        var a=create(true,"customer@example.test");
        db.update("update appointments set status='COMPLETED' where id=?",a.id());
        db.update("update outbox set available_at=now() where tenant_id=?",tenant);
        worker.process(tenant);assertEquals(2,count("SKIPPED"));verify(gmail,never()).send(any(),anyString(),anyString(),anyString());
    }
}
