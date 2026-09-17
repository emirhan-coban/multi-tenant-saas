package com.saas.backend.booking;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.auth.JwtService;
import com.saas.backend.push.PushDeliveryWorker;
import com.saas.backend.push.RecordingPushTransport;
import com.saas.backend.push.WebPushTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Faz 8 uçtan uca güven yaşam döngüsü. Yalnızca "zaman atlaması" (randevuyu geçmişe taşımak) doğrudan SQL ile yapılır;
 * diğer her adım gerçek HTTP uçlarından, gerçek güvenlik filtreleri ve RLS ile geçer.
 *
 * <pre>
 *  1 NEW müşteri talep oluşturur          → PENDING_APPROVAL, yöneticiye bildirim + push
 *  2 Yönetici onaylar                     → APPROVED, CONFIRMED randevu, müşteriye bildirim + push
 *  3 Ziyaret: müşteri kod üretir, berber doğrular (hatalı deneme sayılır)
 *  4 Randevu tamamlanır                   → NEW → TRUSTED (VERIFIED_COMPLETED_VISIT kanıtı)
 *  5 İkinci talep                         → AUTO_CONFIRMED, onay kuyruğuna düşmez
 *  6 Yönetici güveni iptal eder           → TRUSTED → REVIEW_REQUIRED, önceki otomatik randevu korunur
 *  7 Üçüncü talep                         → yeniden PENDING_APPROVAL
 *  8 Güven geri verilir + kill switch     → otomatik onay bayrağı kapalıyken TRUSTED müşteri de onaya düşer
 *  9 Müşteri engellenir                   → CUSTOMER_BLOCKED; olay zinciri eksiksiz
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecordingPushTransport.Config.class)
class TrustLifecycleEndToEndTests extends PostgresTestBase {
    private static final String OWNER_PUSH = "https://fcm.googleapis.com/fcm/send/owner-" + UUID.randomUUID();
    private static final String CUSTOMER_PUSH = "https://updates.push.services.mozilla.com/wpush/v2/customer-" + UUID.randomUUID();

    // Faz 9: fixture SQL'i şema sahibiyle çalışır; uygulama kodu kısıtlı saas_app rolündedir.
    final JdbcTemplate db = ownerJdbc();
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired PushDeliveryWorker pushWorker;
    @Autowired RecordingPushTransport pushTransport;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private UUID tenant, owner, staff, service;
    private String slug;
    private LocalDate date;

    @BeforeEach
    void setup() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHDB");
        pushTransport.clear();
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); staff = UUID.randomUUID(); service = UUID.randomUUID();
        slug = "trust-e2e-" + tenant;
        db.update("insert into tenants(id,name,slug,timezone,plan) values (?,?,?,?,?)", tenant, "Pilot Berber", slug, "Europe/Istanbul", "PRO");
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", owner, owner + "@example.test", "hash", "Emre Usta");
        db.update("insert into memberships(user_id,tenant_id,role) values (?,?,'OWNER')", owner, tenant);
        db.update("insert into staff(id,tenant_id,user_id,display_name,chair_key) values (?,?,?,?,?)", staff, tenant, owner, "Emre Usta", "chair-e2e");
        db.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,?,?,?)", service, tenant, "Saç Kesimi", 30, 10, 600);
        date = LocalDate.now(ZoneId.of("Europe/Istanbul")).plusDays(2);
        db.update("insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (?,?,?,?,?)",
                tenant, staff, date.getDayOfWeek().getValue() - 1, LocalTime.of(8, 0), LocalTime.of(22, 0));
        // Senaryo aynı gün beş talep oluşturur; limitler senaryonun konusu değildir.
        db.update("update booking_settings set max_daily_bookings=10,max_future_appointments=5 where tenant_id=?", tenant);
    }

    @Test
    void newCustomerEarnsTrustThroughVerifiedVisitThenLosesItAndAutoConfirmationStops() throws Exception {
        String ownerToken = jwt.generateAccessToken(owner, tenant, "OWNER");
        var ownerDevice = new WebPushTestClient(OWNER_PUSH);
        assertStatus(204, post("/api/push-subscriptions", ownerDevice.subscriptionJson(), ownerToken, null));

        var customer = register("pilot-" + UUID.randomUUID() + "@example.test");
        var customerDevice = new WebPushTestClient(CUSTOMER_PUSH);
        assertStatus(204, post("/api/customer/v1/push-subscriptions", customerDevice.subscriptionJson(), customer.token(), null));
        List<String> slots = slots();
        assertTrue(slots.size() >= 21, "Senaryo için yeterli boş saat olmalı: " + slots.size());

        // 1) NEW müşteri → onay bekleyen talep
        var first = json.readTree(assertStatus(201, post("/api/customer/v1/booking-requests", body(slots.get(0)), customer.token(), UUID.randomUUID().toString())).body());
        assertEquals("PENDING_APPROVAL", first.get("status").asString());
        assertEquals("REQUIRE_APPROVAL", first.get("decision").asString());
        assertEquals("NEW", first.get("trustState").asString());
        UUID firstRequest = UUID.fromString(first.get("id").asString());
        UUID tenantCustomer = db.queryForObject("select customer_id from booking_requests where id=?", UUID.class, firstRequest);
        var queue = json.readTree(assertStatus(200, get("/api/booking-requests", ownerToken)).body());
        assertTrue(containsId(queue, firstRequest), queue.toString());
        assertTrue(hasEvent(get("/api/notifications/in-app", ownerToken), "BOOKING_REQUEST_CREATED"));

        // Yöneticinin telefonuna şifreli push gider; içerik müşterinin kişisel verisini taşımaz.
        assertTrue(pushWorker.processBatch() >= 1);
        var ownerPush = pushTransport.sentTo(OWNER_PUSH);
        assertEquals(1, ownerPush.size());
        assertEquals("aes128gcm", ownerPush.getFirst().headers().get("Content-Encoding"));
        assertTrue(ownerPush.getFirst().headers().get("Authorization").startsWith("vapid t="));
        var ownerPayload = json.readTree(ownerDevice.decrypt(ownerPush.getFirst().body()));
        assertEquals("Yeni rezervasyon talebi", ownerPayload.get("title").asString());
        assertEquals("/app/booking-requests", ownerPayload.get("url").asString());
        assertFalse(ownerPayload.toString().contains("Web Customer"), "Kilit ekranı bildirimi müşteri adını içermemeli");

        // 2) Onay → kesin randevu
        var approved = json.readTree(assertStatus(200, post("/api/booking-requests/" + firstRequest + "/approve", "{}", ownerToken, UUID.randomUUID().toString())).body());
        assertEquals("APPROVED", approved.get("status").asString());
        UUID firstAppointment = UUID.fromString(approved.get("appointmentId").asString());
        assertEquals("APPROVED", json.readTree(get("/api/customer/v1/booking-requests/" + firstRequest, customer.token()).body()).get("status").asString());
        assertEquals("CONFIRMED", db.queryForObject("select status from appointments where id=?", String.class, firstAppointment));
        assertTrue(hasEvent(get("/api/customer/v1/notifications", customer.token()), "BOOKING_REQUEST_APPROVED"));
        pushWorker.processBatch();
        var approvedPush = json.readTree(customerDevice.decrypt(pushTransport.sentTo(CUSTOMER_PUSH).getLast().body()));
        assertEquals("Randevun onaylandı", approvedPush.get("title").asString());
        assertEquals("/customer/account", approvedPush.get("url").asString());

        // 3) Ziyaret: zaman atlaması — randevu yarım saat önce bitmiş gibi geçmişe taşınır.
        Instant began = Instant.now().minus(Duration.ofMinutes(50)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        db.update("update appointments set starts_at=?,ends_at=?,busy_until=? where id=?", ts(began), ts(began.plusSeconds(1800)), ts(began.plusSeconds(2400)), firstAppointment);
        db.update("update booking_requests set starts_at=?,busy_until=? where id=?", ts(began), ts(began.plusSeconds(2400)), firstRequest);
        var code = json.readTree(assertStatus(200, post("/api/customer/v1/booking-requests/" + firstRequest + "/visit-code", "{}", customer.token(), UUID.randomUUID().toString())).body())
                .get("code").asString();
        String wrong = code.equals("123456") ? "654321" : "123456";
        assertError(post("/api/appointments/" + firstAppointment + "/visit-verification", "{\"code\":\"" + wrong + "\"}", ownerToken, UUID.randomUUID().toString()), 400, "INVALID_VISIT_CODE");
        var verified = json.readTree(assertStatus(200, post("/api/appointments/" + firstAppointment + "/visit-verification", "{\"code\":\"" + code + "\"}", ownerToken, UUID.randomUUID().toString())).body());
        assertTrue(verified.get("verified").asBoolean());
        assertEquals("NEW", trustState(tenantCustomer), "Doğrulama tek başına güven kazandırmaz; randevu tamamlanmalı");

        // 4) Tamamlandı → TRUSTED
        int appointmentVersion = db.queryForObject("select version from appointments where id=?", Integer.class, firstAppointment);
        assertStatus(200, patch("/api/appointments/" + firstAppointment, "{\"version\":" + appointmentVersion + ",\"status\":\"COMPLETED\"}", ownerToken));
        var trusted = db.queryForMap("select trust_state,trust_reason,evidence_appointment_id,completed_count from tenant_customers where id=?", tenantCustomer);
        assertEquals("TRUSTED", trusted.get("trust_state"));
        assertEquals("VERIFIED_COMPLETED_VISIT", trusted.get("trust_reason"));
        assertEquals(firstAppointment, trusted.get("evidence_appointment_id"));
        assertEquals(1, trusted.get("completed_count"));
        assertEquals(1L, db.queryForObject("select count(*) from audit_log where tenant_id=? and action='CUSTOMER_TRUST_AUTOMATED' and entity_id=?", Long.class, tenant, tenantCustomer));

        // 5) Güvenilir müşteri → otomatik onay, kuyruk atlanır
        String autoKey = UUID.randomUUID().toString();
        var automatic = json.readTree(assertStatus(201, post("/api/customer/v1/booking-requests", body(slots.get(5)), customer.token(), autoKey)).body());
        assertEquals("AUTO_CONFIRMED", automatic.get("status").asString());
        assertEquals("AUTO_CONFIRM", automatic.get("decision").asString());
        assertEquals("APPOINTMENT", automatic.get("resultType").asString());
        UUID autoRequest = UUID.fromString(automatic.get("id").asString());
        UUID autoAppointment = UUID.fromString(automatic.get("appointmentId").asString());
        assertEquals(autoRequest.toString(), json.readTree(post("/api/customer/v1/booking-requests", body(slots.get(5)), customer.token(), autoKey).body()).get("id").asString(),
                "Aynı idempotency anahtarı ikinci randevu üretmemeli");
        assertFalse(containsId(json.readTree(get("/api/booking-requests", ownerToken).body()), autoRequest));
        assertTrue(hasEvent(get("/api/customer/v1/notifications", customer.token()), "APPOINTMENT_CONFIRMED"));
        assertTrue(hasEvent(get("/api/notifications/in-app", ownerToken), "APPOINTMENT_AUTO_CONFIRMED"));

        // 6) Güven iptali
        var revoked = json.readTree(assertStatus(200, patch("/api/booking-customers/" + tenantCustomer + "/trust",
                "{\"trustState\":\"REVIEW_REQUIRED\",\"expectedVersion\":" + trustVersion(tenantCustomer) + ",\"reason\":\"Pilot: şüpheli tekrar eden talepler\"}", ownerToken)).body());
        assertEquals("REVIEW_REQUIRED", revoked.get("trustState").asString());
        assertEquals("CONFIRMED", db.queryForObject("select status from appointments where id=?", String.class, autoAppointment),
                "Güven iptali geçmişe dönük randevu iptal etmez");

        // 7) İptal sonrası talep yeniden onaya düşer
        var reviewed = json.readTree(assertStatus(201, post("/api/customer/v1/booking-requests", body(slots.get(10)), customer.token(), UUID.randomUUID().toString())).body());
        assertEquals("PENDING_APPROVAL", reviewed.get("status").asString());
        assertEquals("REVIEW_REQUIRED", reviewed.get("trustState").asString());
        UUID reviewedRequest = UUID.fromString(reviewed.get("id").asString());
        assertEquals(2L, db.queryForObject("select count(*) from appointments where customer_id=?", Long.class, tenantCustomer));
        assertStatus(200, post("/api/booking-requests/" + reviewedRequest + "/reject", "{\"reason\":\"Pilot senaryosu\"}", ownerToken, UUID.randomUUID().toString()));

        // 8) Güven geri verilir ama kontrollü yayın anahtarı otomatik onayı kapatır
        assertStatus(200, patch("/api/booking-customers/" + tenantCustomer + "/trust",
                "{\"trustState\":\"TRUSTED\",\"expectedVersion\":" + trustVersion(tenantCustomer) + ",\"reason\":\"İnceleme tamamlandı\"}", ownerToken));
        assertStatus(200, put("/api/feature-flags/booking.trusted_auto_confirm", "{\"enabled\":false,\"reason\":\"Pilot haftası 1: manuel onay\"}", ownerToken));
        var gated = json.readTree(assertStatus(201, post("/api/customer/v1/booking-requests", body(slots.get(15)), customer.token(), UUID.randomUUID().toString())).body());
        assertEquals("PENDING_APPROVAL", gated.get("status").asString());
        assertEquals("TRUSTED", gated.get("trustState").asString());
        assertEquals(1L, db.queryForObject("select count(*) from audit_log where tenant_id=? and action='FEATURE_FLAG_CHANGED'", Long.class, tenant));

        // 9) Engelleme
        assertStatus(200, patch("/api/booking-customers/" + tenantCustomer + "/trust",
                "{\"trustState\":\"BLOCKED\",\"expectedVersion\":" + trustVersion(tenantCustomer) + ",\"reason\":\"Sahte talepler\"}", ownerToken));
        assertError(post("/api/customer/v1/booking-requests", body(slots.get(20)), customer.token(), UUID.randomUUID().toString()), 403, "CUSTOMER_BLOCKED");

        var events = json.readTree(assertStatus(200, get("/api/booking-customers/" + tenantCustomer + "/trust-events", ownerToken)).body());
        List<String> chain = new ArrayList<>();
        for (JsonNode event : events) chain.add(event.get("fromState").asString() + "->" + event.get("toState").asString());
        var expected = List.of("NEW->TRUSTED", "TRUSTED->REVIEW_REQUIRED", "REVIEW_REQUIRED->TRUSTED", "TRUSTED->BLOCKED");
        assertTrue(chain.equals(expected) || chain.equals(expected.reversed()), "Güven olay zinciri: " + chain);
        assertEquals(4L, db.queryForObject("select count(*) from customer_trust_events where customer_id=?", Long.class, tenantCustomer));
    }

    private List<String> slots() throws Exception {
        var response = assertStatus(200, get("/api/public/v1/shops/" + slug + "/availability?serviceId=" + service + "&staffId=" + staff + "&date=" + date, null));
        List<String> result = new ArrayList<>();
        for (JsonNode slot : json.readTree(response.body()).get("slots")) result.add(slot.get("startsAt").asString());
        return result;
    }

    private String body(String startsAt) {
        return "{\"tenantSlug\":\"%s\",\"serviceId\":\"%s\",\"staffId\":\"%s\",\"startsAt\":\"%s\",\"note\":null}".formatted(slug, service, staff, startsAt);
    }

    private String trustState(UUID tenantCustomer) {
        return db.queryForObject("select trust_state from tenant_customers where id=?", String.class, tenantCustomer);
    }

    private int trustVersion(UUID tenantCustomer) {
        return db.queryForObject("select version from tenant_customers where id=?", Integer.class, tenantCustomer);
    }

    private boolean containsId(JsonNode rows, UUID id) {
        for (JsonNode row : rows) if (row.get("id").asString().equals(id.toString())) return true;
        return false;
    }

    private boolean hasEvent(HttpResponse<String> response, String eventType) {
        assertEquals(200, response.statusCode(), response.body());
        for (JsonNode row : json.readTree(response.body())) if (row.get("eventType").asString().equals(eventType)) return true;
        return false;
    }

    private Customer register(String email) throws Exception {
        var response = assertStatus(201, post("/api/customer/v1/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"Web Customer\",\"phone\":\"+90 555 111 22 33\"}", null, null));
        var body = json.readTree(response.body());
        return new Customer(UUID.fromString(body.get("customer").get("id").asString()), body.get("accessToken").asString());
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token, String key) throws Exception {
        return send("POST", path, body, token, key);
    }

    private HttpResponse<String> patch(String path, String body, String token) throws Exception {
        return send("PATCH", path, body, token, null);
    }

    private HttpResponse<String> put(String path, String body, String token) throws Exception {
        return send("PUT", path, body, token, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String token, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> assertStatus(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        return response;
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).get("code").asString(), response.body());
    }

    private record Customer(UUID id, String token) {}
}
