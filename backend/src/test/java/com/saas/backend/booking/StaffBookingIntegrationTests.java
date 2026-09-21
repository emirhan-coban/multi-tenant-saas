package com.saas.backend.booking;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.appointment.Appointment;
import com.saas.backend.appointment.AppointmentDtos;
import com.saas.backend.appointment.AppointmentService;
import com.saas.backend.auth.JwtService;
import com.saas.backend.config.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffBookingIntegrationTests extends PostgresTestBase {
    // Faz 9: fixture SQL'i şema sahibiyle çalışır; uygulama kodu kısıtlı saas_app rolündedir.
    final JdbcTemplate db = ownerJdbc();
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired AppointmentService appointments;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private UUID tenant, owner, staff, service, account, tenantCustomer, requestId;
    private Instant start;

    @BeforeEach
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); staff = UUID.randomUUID();
        service = UUID.randomUUID(); account = UUID.randomUUID(); tenantCustomer = UUID.randomUUID(); requestId = UUID.randomUUID();
        start = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        db.update("insert into tenants(id,name,slug,timezone,plan) values (?,?,?,?,?)", tenant, "Approval Shop", "approval-" + tenant, "Europe/Istanbul", "PRO");
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", owner, owner + "@example.test", "hash", "Owner User");
        db.update("insert into memberships(user_id,tenant_id,role) values (?,?,'OWNER')", owner, tenant);
        db.update("insert into staff(id,tenant_id,user_id,display_name) values (?,?,?,?)", staff, tenant, owner, "Deniz Usta");
        db.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,?,?,?)", service, tenant, "Saç Kesimi", 30, 10, 450);
        db.update("insert into customer_accounts(id,email,password_hash,full_name,phone) values (?,?,?,?,?)", account, account + "@example.test", "hash", "Ada Müşteri", "+90 555 000 00 00");
        db.update("insert into tenant_customers(id,tenant_id,customer_account_id) values (?,?,?)", tenantCustomer, tenant, account);
        pending(requestId, tenantCustomer, start);
    }

    @Test
    void managementCanListEnrichedQueueButStaffAndOtherTenantCannotSeeIt() throws Exception {
        var response = get("/api/booking-requests", token("OWNER"));
        assertEquals(200, response.statusCode(), response.body());
        var rows = json.readTree(response.body());
        assertEquals(1, rows.size());
        assertEquals("Ada Müşteri", rows.get(0).get("customerName").asString());
        assertEquals("Saç Kesimi", rows.get(0).get("serviceName").asString());
        assertEquals("Deniz Usta", rows.get(0).get("staffName").asString());
        assertEquals("NEW", rows.get(0).get("trustState").asString());
        assertError(get("/api/booking-requests", token("STAFF")), 403, "FORBIDDEN");

        UUID other = UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)", other, "Other", "other-" + other, "Europe/Istanbul");
        db.update("insert into memberships(user_id,tenant_id,role) values (?,?,'OWNER')", owner, other);
        var foreign = get("/api/booking-requests", jwt.generateAccessToken(owner, other, "OWNER"));
        assertEquals(200, foreign.statusCode());
        assertEquals(0, json.readTree(foreign.body()).size());
    }

    @Test
    void approveIsAtomicAuditedAndIdempotent() throws Exception {
        String key = UUID.randomUUID().toString();
        var approved = post("/api/booking-requests/" + requestId + "/approve", "{}", token("MANAGER"), key);
        assertEquals(200, approved.statusCode(), approved.body());
        var body = json.readTree(approved.body());
        assertEquals("APPROVED", body.get("status").asString());
        UUID appointmentId = UUID.fromString(body.get("appointmentId").asString());
        var appointment = db.queryForMap("select customer_id,customer_email,email_notifications,price_amount from appointments where id=?", appointmentId);
        assertEquals(tenantCustomer, appointment.get("customer_id"));
        assertEquals(account + "@example.test", appointment.get("customer_email"));
        assertEquals(true, appointment.get("email_notifications"));
        assertEquals("450.00", appointment.get("price_amount").toString());
        assertEquals(1L, db.queryForObject("select count(*) from audit_log where tenant_id=? and action='BOOKING_REQUEST_APPROVED'", Long.class, tenant));
        assertTrue(db.queryForObject("select count(*) from outbox where tenant_id=? and appointment_id=?", Long.class, tenant, appointmentId) >= 2);

        var replay = post("/api/booking-requests/" + requestId + "/approve", "{}", token("OWNER"), key);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(appointmentId.toString(), json.readTree(replay.body()).get("appointmentId").asString());
        assertEquals(1L, db.queryForObject("select count(*) from appointments where id=?", Long.class, appointmentId));
        assertError(post("/api/booking-requests/" + requestId + "/reject", "{}", token("OWNER"), UUID.randomUUID().toString()),
                409, "BOOKING_ALREADY_DECIDED");
    }

    @Test
    void rejectionReleasesHoldAndReplaysOnlyTheSameDecision() throws Exception {
        String key = UUID.randomUUID().toString();
        var rejected = post("/api/booking-requests/" + requestId + "/reject", "{\"reason\":\"Telefon teyidi alınamadı\"}", token("OWNER"), key);
        assertEquals(200, rejected.statusCode(), rejected.body());
        var body = json.readTree(rejected.body());
        assertEquals("REJECTED", body.get("status").asString());
        assertEquals("Telefon teyidi alınamadı", body.get("rejectionReason").asString());
        assertTrue(body.get("holdExpiresAt").isNull());
        assertEquals(0L, db.queryForObject("select count(*) from appointments where tenant_id=?", Long.class, tenant));
        assertEquals(200, post("/api/booking-requests/" + requestId + "/reject", "{}", token("MANAGER"), key).statusCode());
        assertError(post("/api/booking-requests/" + requestId + "/approve", "{}", token("OWNER"), UUID.randomUUID().toString()),
                409, "BOOKING_ALREADY_DECIDED");
    }

    @Test
    void approveRejectRaceHasExactlyOneWinner() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approve = executor.submit(() -> post("/api/booking-requests/" + requestId + "/approve", "{}", token("OWNER"), UUID.randomUUID().toString()));
            var reject = executor.submit(() -> post("/api/booking-requests/" + requestId + "/reject", "{}", token("MANAGER"), UUID.randomUUID().toString()));
            var first = approve.get(); var second = reject.get();
            assertEquals(1, java.util.stream.Stream.of(first, second).filter(r -> r.statusCode() == 200).count(), first.body() + second.body());
            assertEquals(1, java.util.stream.Stream.of(first, second).filter(r -> r.statusCode() == 409).count(), first.body() + second.body());
        }
        String status = db.queryForObject("select status from booking_requests where id=?", String.class, requestId);
        assertTrue(status.equals("APPROVED") || status.equals("REJECTED"));
        assertEquals(status.equals("APPROVED") ? 1L : 0L,
                db.queryForObject("select count(*) from appointments where tenant_id=?", Long.class, tenant));
    }

    @Test
    void expiredRequestCannotBeApproved() throws Exception {
        db.update("update booking_requests set hold_expires_at=now()-interval '1 second' where id=?", requestId);
        assertError(post("/api/booking-requests/" + requestId + "/approve", "{}", token("OWNER"), UUID.randomUUID().toString()),
                409, "BOOKING_REQUEST_EXPIRED");
        assertEquals("EXPIRED", db.queryForObject("select status from booking_requests where id=?", String.class, requestId));
    }

    @Test
    void appointmentOutcomeAndManualReviewUpdateTenantTrust() throws Exception {
        UUID unverifiedAppointment = pastAppointment(tenantCustomer, Appointment.Status.CONFIRMED);
        appointments.patch(new AuthenticatedUser(owner, tenant, "OWNER"), unverifiedAppointment,
                new AppointmentDtos.Patch(0, null, Appointment.Status.COMPLETED, null));
        var unverified = db.queryForMap("select trust_state,completed_count from tenant_customers where id=?", tenantCustomer);
        assertEquals("NEW", unverified.get("trust_state"));
        assertEquals(1, unverified.get("completed_count"));

        UUID verifiedAppointment = pastAppointment(tenantCustomer, Appointment.Status.CONFIRMED);
        UUID verifiedRequest = approvedBooking(verifiedAppointment);
        var issued = post("/api/customer/v1/booking-requests/" + verifiedRequest + "/visit-code", "{}",
                jwt.generateCustomerAccessToken(account), UUID.randomUUID().toString());
        assertEquals(200, issued.statusCode(), issued.body());
        String code = json.readTree(issued.body()).get("code").asString();
        String wrongCode = code.equals("000000") ? "999999" : "000000";
        String storedHash = db.queryForObject("select code_hash from visit_verifications where appointment_id=?", String.class, verifiedAppointment);
        assertNotEquals(code, storedHash);
        assertEquals(64, storedHash.length());

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertError(post("/api/appointments/" + verifiedAppointment + "/visit-verification",
                    "{\"code\":\"" + wrongCode + "\"}", token("MANAGER"), UUID.randomUUID().toString()), 400, "INVALID_VISIT_CODE");
        }
        assertError(post("/api/appointments/" + verifiedAppointment + "/visit-verification",
                "{\"code\":\"" + wrongCode + "\"}", token("MANAGER"), UUID.randomUUID().toString()), 400, "VISIT_CODE_LOCKED");
        assertEquals(5, db.queryForObject("select attempt_count from visit_verifications where appointment_id=?", Integer.class, verifiedAppointment));
        assertError(post("/api/appointments/" + verifiedAppointment + "/visit-verification",
                "{\"code\":\"" + code + "\"}", token("MANAGER"), UUID.randomUUID().toString()), 400, "VISIT_CODE_LOCKED");

        var reissued = post("/api/customer/v1/booking-requests/" + verifiedRequest + "/visit-code", "{}",
                jwt.generateCustomerAccessToken(account), UUID.randomUUID().toString());
        assertEquals(200, reissued.statusCode(), reissued.body());
        String freshCode = json.readTree(reissued.body()).get("code").asString();
        var verified = post("/api/appointments/" + verifiedAppointment + "/visit-verification",
                "{\"code\":\"" + freshCode + "\"}", token("MANAGER"), UUID.randomUUID().toString());
        assertEquals(200, verified.statusCode(), verified.body());
        assertTrue(json.readTree(verified.body()).get("verified").asBoolean());

        appointments.patch(new AuthenticatedUser(owner, tenant, "OWNER"), verifiedAppointment,
                new AppointmentDtos.Patch(0, null, Appointment.Status.COMPLETED, null));
        var trusted = db.queryForMap("select trust_state,completed_count,no_show_count,evidence_appointment_id,version from tenant_customers where id=?", tenantCustomer);
        assertEquals("TRUSTED", trusted.get("trust_state"));
        assertEquals(2, trusted.get("completed_count"));
        assertEquals(verifiedAppointment, trusted.get("evidence_appointment_id"));
        assertEquals(1L, db.queryForObject("select count(*) from customer_trust_events where customer_id=? and evidence_appointment_id=?", Long.class, tenantCustomer, verifiedAppointment));

        UUID noShowAppointment = pastAppointment(tenantCustomer, Appointment.Status.CONFIRMED);
        appointments.patch(new AuthenticatedUser(owner, tenant, "OWNER"), noShowAppointment,
                new AppointmentDtos.Patch(0, null, Appointment.Status.NO_SHOW, null));
        var review = db.queryForMap("select trust_state,completed_count,no_show_count from tenant_customers where id=?", tenantCustomer);
        assertEquals("REVIEW_REQUIRED", review.get("trust_state"));
        assertEquals(2, review.get("completed_count"));
        assertEquals(1, review.get("no_show_count"));

        int trustVersion = db.queryForObject("select version from tenant_customers where id=?", Integer.class, tenantCustomer);
        var blocked = patch("/api/booking-customers/" + tenantCustomer + "/trust",
                "{\"trustState\":\"BLOCKED\",\"expectedVersion\":" + trustVersion + ",\"reason\":\"Tekrarlanan sahte talepler\"}", token("OWNER"));
        assertEquals(200, blocked.statusCode(), blocked.body());
        assertEquals("BLOCKED", json.readTree(blocked.body()).get("trustState").asString());
        assertError(post("/api/booking-requests/" + requestId + "/approve", "{}", token("OWNER"), UUID.randomUUID().toString()),
                403, "CUSTOMER_BLOCKED");
        assertError(patch("/api/booking-customers/" + tenantCustomer + "/trust",
                "{\"trustState\":\"TRUSTED\",\"expectedVersion\":" + json.readTree(blocked.body()).get("version").asInt() + "}", token("OWNER")), 400, "TRUST_REASON_REQUIRED");
        assertError(patch("/api/booking-customers/" + tenantCustomer + "/trust",
                "{\"trustState\":\"TRUSTED\",\"expectedVersion\":" + json.readTree(blocked.body()).get("version").asInt() + ",\"reason\":\"Yetkisiz deneme\"}", token("STAFF")), 403, "FORBIDDEN");
        assertError(patch("/api/booking-customers/" + tenantCustomer + "/trust",
                "{\"trustState\":\"TRUSTED\",\"expectedVersion\":" + trustVersion + ",\"reason\":\"Eski ekran\"}", token("OWNER")), 409, "STALE_VERSION");
    }

    @Test
    void visitCodeIsOwnerScopedAndExpiredCodesCannotBeVerified() throws Exception {
        UUID appointmentId = pastAppointment(tenantCustomer, Appointment.Status.CONFIRMED);
        UUID bookingId = approvedBooking(appointmentId);
        UUID stranger = UUID.randomUUID();
        db.update("insert into customer_accounts(id,email,password_hash,full_name) values (?,?,?,?)",
                stranger, stranger + "@example.test", "hash", "Baska Musteri");

        assertError(post("/api/customer/v1/booking-requests/" + bookingId + "/visit-code", "{}",
                jwt.generateCustomerAccessToken(stranger), UUID.randomUUID().toString()), 404, "NOT_FOUND");
        var issued = post("/api/customer/v1/booking-requests/" + bookingId + "/visit-code", "{}",
                jwt.generateCustomerAccessToken(account), UUID.randomUUID().toString());
        assertEquals(200, issued.statusCode(), issued.body());
        String code = json.readTree(issued.body()).get("code").asString();
        db.update("update visit_verifications set expires_at=now()-interval '1 second' where appointment_id=?", appointmentId);
        assertError(post("/api/appointments/" + appointmentId + "/visit-verification",
                "{\"code\":\"" + code + "\"}", token("OWNER"), UUID.randomUUID().toString()), 400, "VISIT_CODE_EXPIRED");
        var expired = db.queryForMap("select attempt_count,invalidated_at,verified_at from visit_verifications where appointment_id=?", appointmentId);
        assertEquals(1, expired.get("attempt_count"));
        assertNotNull(expired.get("invalidated_at"));
        assertNull(expired.get("verified_at"));

        UUID otherTenant = UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)", otherTenant, "Diger", "diger-" + otherTenant, "Europe/Istanbul");
        db.update("insert into memberships(user_id,tenant_id,role) values (?,?,'OWNER')", owner, otherTenant);
        assertError(post("/api/appointments/" + appointmentId + "/visit-verification",
                "{\"code\":\"" + code + "\"}", jwt.generateAccessToken(owner, otherTenant, "OWNER"), UUID.randomUUID().toString()), 404, "NOT_FOUND");
    }

    @Test
    void appointmentCannotBeCompletedBeforeItsEndTime() throws Exception {
        UUID appointmentId = UUID.randomUUID();
        Instant begins = Instant.now().minus(Duration.ofMinutes(5));
        db.update("""
                insert into appointments(id,tenant_id,staff_id,service_id,customer_id,customer_name,starts_at,ends_at,busy_until,duration_min,buffer_min,status,price_amount)
                values (?,?,?,?,?,?,?, ?, ?,30,10,'CONFIRMED',450)
                """, appointmentId, tenant, staff, service, tenantCustomer, "Ada Müşteri",
                OffsetDateTime.ofInstant(begins, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(begins.plus(Duration.ofMinutes(30)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(begins.plus(Duration.ofMinutes(40)), ZoneOffset.UTC));

        assertError(patch("/api/appointments/" + appointmentId,
                "{\"version\":0,\"status\":\"COMPLETED\"}", token("OWNER")), 409, "INVALID_TRANSITION");
    }

    private void pending(UUID id, UUID customer, Instant startsAt) {
        db.update("""
                insert into booking_requests(id,tenant_id,customer_id,staff_id,service_id,starts_at,busy_until,note,status,decision,hold_expires_at,idempotency_key,request_hash)
                values (?,?,?,?,?,?,?,'Müşteri notu','PENDING_APPROVAL','REQUIRE_APPROVAL',now()+interval '15 minutes',?,?)
                """, id, tenant, customer, staff, service,
                OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(startsAt.plus(Duration.ofMinutes(40)), ZoneOffset.UTC),
                UUID.randomUUID().toString(), "a".repeat(64));
    }

    private UUID pastAppointment(UUID customer, Appointment.Status status) {
        UUID id = UUID.randomUUID();
        long existing = db.queryForObject("select count(*) from appointments where tenant_id=?", Long.class, tenant);
        Instant starts = Instant.now().minus(Duration.ofHours(2 + existing * 2)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        db.update("""
                insert into appointments(id,tenant_id,staff_id,service_id,customer_id,customer_name,starts_at,ends_at,busy_until,duration_min,buffer_min,status,price_amount)
                values (?,?,?,?,?,?,?, ?, ?,30,10,?,450)
                """, id, tenant, staff, service, customer, "Ada Müşteri",
                OffsetDateTime.ofInstant(starts, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(starts.plus(Duration.ofMinutes(30)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(starts.plus(Duration.ofMinutes(40)), ZoneOffset.UTC), status.name());
        return id;
    }

    private UUID approvedBooking(UUID appointmentId) {
        UUID id = UUID.randomUUID();
        var appointment = db.queryForMap("select starts_at,busy_until from appointments where id=?", appointmentId);
        db.update("""
                insert into booking_requests(id,tenant_id,customer_id,staff_id,service_id,starts_at,busy_until,status,decision,
                                             idempotency_key,request_hash,appointment_id,decided_by,decided_at,decision_key,decision_action)
                values (?,?,?,?,?,?,?,'APPROVED','REQUIRE_APPROVAL',?,?,?,?,now(),?,'APPROVE')
                """, id, tenant, tenantCustomer, staff, service, appointment.get("starts_at"), appointment.get("busy_until"),
                UUID.randomUUID().toString(), "b".repeat(64), appointmentId, owner, UUID.randomUUID());
        return id;
    }

    private String token(String role) { return jwt.generateAccessToken(owner, tenant, role); }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path, String body, String token, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> patch(String path, String body, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).get("code").asString(), response.body());
    }
}
