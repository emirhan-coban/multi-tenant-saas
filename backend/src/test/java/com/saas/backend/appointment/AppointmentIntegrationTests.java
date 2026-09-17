package com.saas.backend.appointment;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.auth.JwtService;
import com.saas.backend.config.AuthenticatedUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppointmentIntegrationTests extends PostgresTestBase {
    @Autowired AppointmentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    UUID tenant, user, staff, offering;
    AuthenticatedUser owner;
    final LocalDate date=LocalDate.of(2030,1,7); // Monday
    final Instant start=Instant.parse("2030-01-07T06:00:00Z"); // 09:00 Istanbul
    final HttpClient http=HttpClient.newHttpClient();

    @BeforeEach void setup() {
        tenant=UUID.randomUUID(); user=UUID.randomUUID(); staff=UUID.randomUUID(); offering=UUID.randomUUID();
        jdbc.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)",tenant,"QA",tenant.toString(),"Europe/Istanbul");
        jdbc.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)",user,user+"@example.test","unused","QA");
        jdbc.update("insert into staff(id,tenant_id,user_id,display_name) values (?,?,?,?)",staff,tenant,user,"QA");
        jdbc.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,30,10,100)",offering,tenant,"QA");
        jdbc.update("insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (?,?,0,'09:00','18:00')",tenant,staff);
        owner=new AuthenticatedUser(user,tenant,"OWNER");
    }
    AppointmentDtos.Create input(Instant time) { return new AppointmentDtos.Create(staff,offering,time,"Test",null,null); }
    AppointmentDtos.Response create(String key,Instant time) {
        return json.readValue(service.create(owner,key,input(time)),AppointmentDtos.Response.class);
    }
    HttpResponse<String> post(String key,String body,String role) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/appointments"))
            .timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+jwt.generateAccessToken(user,tenant,role))
            .header("Content-Type","application/json").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

    @Test void availabilityUsesTenantZoneAndSubtractsLeaveAndBuffer() {
        var before=service.availability(owner,staff,offering,date);
        assertEquals("Europe/Istanbul",before.timezone());
        assertEquals(start,before.slots().getFirst().startsAt());
        assertEquals(13,before.slots().size());
        var a=create("one",start);
        assertEquals(start.plusSeconds(1800),a.endsAt());
        assertEquals(start.plusSeconds(2400),a.busyUntil());
        var after=service.availability(owner,staff,offering,date);
        assertEquals(start.plusSeconds(2400),after.slots().getFirst().startsAt());
        jdbc.update("insert into time_off(tenant_id,staff_id,starts_at,ends_at) values (?,?,?::timestamptz,?::timestamptz)",
            tenant,staff,start.plusSeconds(2400).toString(),start.plusSeconds(4800).toString());
        assertEquals(start.plusSeconds(4800),service.availability(owner,staff,offering,date).slots().getFirst().startsAt());
    }

    @Test void sameKeyReturnsOriginalResponseEvenAfterPatch() {
        String first=service.create(owner,"retry",input(start));
        var a=json.readValue(first,AppointmentDtos.Response.class);
        service.patch(owner,a.id(),new AppointmentDtos.Patch(a.version(),null,Appointment.Status.CANCELLED,null));
        assertEquals(first,service.create(owner,"retry",input(start)));
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.create(owner,"retry",input(start.plusSeconds(2400))));
        assertEquals(1,jdbc.queryForObject("select count(*) from appointments where tenant_id=?",Integer.class,tenant));
    }

    @Test void fiftyHttpRequestsForSameSlotOnlyOneSucceeds() throws Exception {
        var gate=new CountDownLatch(1);
        String body=json.writeValueAsString(input(start));
        var futures=new ArrayList<Future<Integer>>();
        try(var pool=Executors.newFixedThreadPool(50)) {
            for(int i=0;i<50;i++) { final String key="race-"+i;
                futures.add(pool.submit(()->{gate.await();return post(key,body,"OWNER").statusCode();}));
            }
            gate.countDown();
            var statuses=new ArrayList<Integer>();
            for(var f:futures) statuses.add(f.get(90,TimeUnit.SECONDS));
            assertEquals(1,statuses.stream().filter(s->s==201).count(),statuses.toString());
            assertEquals(49,statuses.stream().filter(s->s==409).count(),statuses.toString());
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from appointments where tenant_id=?",Integer.class,tenant));
    }

    @Test void concurrentSameKeyReturnsIdenticalResults() throws Exception {
        var gate=new CountDownLatch(1);
        String body=json.writeValueAsString(input(start));
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{gate.await();return post("same-key",body,"OWNER");}));
            gate.countDown();
            var results=new HashSet<String>();
            for(var f:futures) { var response=f.get(60,TimeUnit.SECONDS); assertEquals(201,response.statusCode()); results.add(response.body()); }
            assertEquals(1,results.size());
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from appointments where tenant_id=?",Integer.class,tenant));
    }

    @Test void databaseConstraintRejectsOverlapEvenWithoutApplicationValidation() {
        create("first",start);
        var error=assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->raw(start.plusSeconds(1800)));
        assertTrue(error.getMostSpecificCause().getMessage().contains("no_overlap"));
        raw(start.plusSeconds(2400)); // half-open intervals permit exact adjacency.
    }
    void raw(Instant time) {
        jdbc.update("insert into appointments(tenant_id,staff_id,service_id,starts_at,ends_at,busy_until,duration_min,buffer_min) values (?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,30,10)",
            tenant,staff,offering,time.toString(),time.plusSeconds(1800).toString(),time.plusSeconds(2400).toString());
    }

    @Test void cancelledSlotCanBeBookedAgainAndTerminalStateCannotChange() {
        var a=create("first",start);
        var cancelled=service.patch(owner,a.id(),new AppointmentDtos.Patch(a.version(),null,Appointment.Status.CANCELLED,null));
        assertEquals(1,cancelled.version());
        create("replacement",start);
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.patch(owner,a.id(),new AppointmentDtos.Patch(1,null,Appointment.Status.CONFIRMED,null)));
    }

    @Test void staleVersionDoesNotOverwriteChanges() {
        var a=create("first",start);
        service.patch(owner,a.id(),new AppointmentDtos.Patch(0,start.plusSeconds(2400),null,"Moved"));
        var e=assertThrows(com.saas.backend.auth.ApiException.class,()->service.patch(owner,a.id(),new AppointmentDtos.Patch(0,null,Appointment.Status.CANCELLED,null)));
        assertEquals("STALE_VERSION",e.getCode());
        var saved=service.list(owner,staff,start,start.plusSeconds(86400)).getFirst();
        assertEquals(start.plusSeconds(2400),saved.startsAt()); assertEquals(1,saved.version());
    }

    @Test void concurrentUpdatesUseJpaVersion() throws Exception {
        var a=create("first",start); var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++) { final String note="note-"+i;
                futures.add(pool.submit(()->{gate.await();try { service.patch(owner,a.id(),new AppointmentDtos.Patch(0,null,null,note));return true; } catch(RuntimeException ex) { return false; }}));
            }
            gate.countDown(); int success=0; for(var f:futures) if(f.get(20,TimeUnit.SECONDS)) success++;
            assertEquals(1,success);
        }
    }

    @Test void foreignTenantCannotReadUpdateOrUseStaffAndService() {
        var a=create("first",start);
        var foreign=new AuthenticatedUser(user,UUID.randomUUID(),"OWNER");
        assertTrue(service.list(foreign,null,start,start.plusSeconds(86400)).isEmpty());
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.patch(foreign,a.id(),new AppointmentDtos.Patch(0,null,Appointment.Status.CANCELLED,null)));
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.availability(foreign,staff,offering,date));
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.create(foreign,"foreign",input(start)));
    }

    @Test void failedRequestDoesNotReserveIdempotencyKey() {
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.create(owner,"retry",input(start.minusSeconds(3600))));
        create("retry",start);
    }

    @Test void apiValidatesInputRoleAndConflictCodes() throws Exception {
        assertEquals(403,post("staff",json.writeValueAsString(input(start)),"STAFF").statusCode());
        assertEquals(400,post("invalid","{}","OWNER").statusCode());
        assertEquals(201,post("first",json.writeValueAsString(input(start)),"MANAGER").statusCode());
        var duplicate=post("second",json.writeValueAsString(input(start)),"OWNER");
        assertEquals(409,duplicate.statusCode()); assertTrue(duplicate.body().contains("SLOT_UNAVAILABLE"));
        var mismatch=post("first",json.writeValueAsString(input(start.plusSeconds(2400))),"OWNER");
        assertEquals(409,mismatch.statusCode()); assertTrue(mismatch.body().contains("IDEMPOTENCY_CONFLICT"));
    }

    @Test void daylightSavingDaysUseRealInstants() {
        jdbc.update("update tenants set timezone='Europe/Berlin' where id=?",tenant);
        jdbc.update("update working_hours set day_of_week=6,start_time='01:00',end_time='04:00' where staff_id=?",staff);
        // 2030-03-31 jumps 02:00 -> 03:00: only two real hours.
        var slots=service.availability(owner,staff,offering,LocalDate.of(2030,3,31)).slots();
        assertEquals(3,slots.size());
        assertEquals(Instant.parse("2030-03-31T00:00:00Z"),slots.getFirst().startsAt());
    }

    @Test void durationSnapshotSurvivesServiceEdit() {
        var a=create("snapshot",start);
        jdbc.update("update services set duration_min=60,buffer_min=20 where id=?",offering);
        var available=service.availability(owner,staff,offering,date,a.id());
        assertEquals(start,available.slots().getFirst().startsAt());
        assertEquals(start.plusSeconds(2400),available.slots().get(1).startsAt());
        var moved=service.patch(owner,a.id(),new AppointmentDtos.Patch(0,start.plusSeconds(2400),null,null));
        assertEquals(Duration.ofMinutes(30),Duration.between(moved.startsAt(),moved.endsAt()));
        assertEquals(Duration.ofMinutes(10),Duration.between(moved.endsAt(),moved.busyUntil()));
    }

    @Test void invalidRangesInactiveStaffAndDeletedServicesAreRejected() {
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.list(owner,null,start,start));
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.create(owner,"past",input(Instant.EPOCH)));
        jdbc.update("update staff set active=false where id=?",staff);
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.availability(owner,staff,offering,date));
        jdbc.update("update staff set active=true where id=?",staff);
        jdbc.update("update services set deleted_at=now() where id=?",offering);
        assertThrows(com.saas.backend.auth.ApiException.class,()->service.availability(owner,staff,offering,date));
    }

    @Test void rlsPolicyUsesRestrictedRoleAndTransactionLocalTenant() throws Exception {
        create("rls",start);
        String role="qa_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create role "+role);
        jdbc.execute("grant usage on schema public to "+role);
        jdbc.execute("grant select on appointments to "+role);
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.execute("set local role "+role);
                statement.execute("select set_config('app.current_tenant','"+UUID.randomUUID()+"',true)");
                try(var rows=statement.executeQuery("select count(*) from appointments")) { rows.next(); assertEquals(0,rows.getInt(1)); }
                statement.execute("select set_config('app.current_tenant','"+tenant+"',true)");
                try(var rows=statement.executeQuery("select count(*) from appointments")) { rows.next(); assertEquals(1,rows.getInt(1)); }
                connection.commit();

                statement.execute("set local role "+role);
                try(var rows=statement.executeQuery("select count(*) from appointments")) { rows.next(); assertEquals(0,rows.getInt(1)); }
            } finally { connection.rollback(); }
        }
    }

    @Test void appointmentPickersOnlyListCurrentTenant() throws Exception {
        for (String path : List.of("/api/staff","/api/services")) {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
                .header("Authorization","Bearer "+jwt.generateAccessToken(user,tenant,"OWNER")).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            var rows=json.readTree(response.body());
            assertEquals(1,rows.size(),response.body());
            assertEquals((path.endsWith("staff")?staff:offering).toString(),rows.get(0).get("id").asString());
        }
    }

    @Test void cancelledAppointmentReleasesSlotButCompletedAndNoShowKeepIt() {
        for (String state : List.of("COMPLETED","NO_SHOW")) {
            var a=create("terminal-"+state,start);
            jdbc.update("update appointments set status=? where id=?",state,a.id());
            assertThrows(com.saas.backend.auth.ApiException.class,()->create("blocked-"+state,start));
            jdbc.update("update appointments set status='CANCELLED' where id=?",a.id());
        }
    }

    @Test void springGapCollapsedHoursYieldNoSlotsAndAutumnDayHasExtraHour() {
        jdbc.update("update tenants set timezone='Europe/Berlin' where id=?",tenant);
        jdbc.update("update working_hours set day_of_week=6,start_time='02:00',end_time='03:00' where staff_id=?",staff);
        assertTrue(service.availability(owner,staff,offering,LocalDate.of(2030,3,31)).slots().isEmpty());
        jdbc.update("update working_hours set start_time='01:00',end_time='04:00' where staff_id=?",staff);
        assertEquals(6,service.availability(owner,staff,offering,LocalDate.of(2030,10,27)).slots().size());
    }
}
