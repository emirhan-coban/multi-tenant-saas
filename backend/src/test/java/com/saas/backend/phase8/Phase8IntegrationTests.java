package com.saas.backend.phase8;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.auth.JwtService;
import com.saas.backend.push.PushDeliveryWorker;
import com.saas.backend.push.RecordingPushTransport;
import com.saas.backend.push.WebPushTestClient;
import com.saas.backend.retention.DataRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecordingPushTransport.Config.class)
@TestPropertySource(properties = "app.ops.metrics-token=phase8-ops-token")
class Phase8IntegrationTests extends PostgresTestBase {
    // Faz 9: fixture SQL'i şema sahibiyle çalışır; uygulama kodu kısıtlı saas_app rolündedir.
    final JdbcTemplate db = ownerJdbc();
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired PushDeliveryWorker pushWorker;
    @Autowired RecordingPushTransport pushTransport;
    @Autowired DataRetentionService retention;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private UUID tenant, owner, staff, service;
    private String slug;
    private LocalDate date;

    @BeforeEach
    void setup() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHDB");
        pushTransport.clear();
        db.update("update push_deliveries set status='DROPPED',finished_at=now() where status='PENDING'");
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); staff = UUID.randomUUID(); service = UUID.randomUUID();
        slug = "phase8-" + tenant;
        db.update("insert into tenants(id,name,slug,timezone,plan) values (?,?,?,?,?)", tenant, "Faz 8 Berber", slug, "Europe/Istanbul", "PRO");
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", owner, owner + "@example.test", "hash", "Owner");
        db.update("insert into memberships(user_id,tenant_id,role) values (?,?,'OWNER')", owner, tenant);
        db.update("insert into staff(id,tenant_id,user_id,display_name,chair_key) values (?,?,?,?,?)", staff, tenant, owner, "Can Usta", "chair-phase8");
        db.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,?,?,?)", service, tenant, "Sakal", 20, 10, 350);
        date = LocalDate.now(ZoneId.of("Europe/Istanbul")).plusDays(3);
        db.update("insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (?,?,?,?,?)",
                tenant, staff, date.getDayOfWeek().getValue() - 1, LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    @Test
    void threeDKillSwitchFallsBackToListWithoutLosingBookingAbility() throws Exception {
        var features = json.readTree(ok(get("/api/public/v1/shops/" + slug + "/features", null)).body());
        assertTrue(features.get("threeD").asBoolean());
        var manifest = json.readTree(ok(get("/api/public/v1/shops/" + slug + "/scene", null)).body());
        String chairKey = manifest.get("chairs").get(0).get("chairKey").asString();
        int version = manifest.get("version").asInt();

        String owner = ownerToken();
        assertError(put("/api/feature-flags/booking.3d", "{\"enabled\":false}", owner), 400, "VALIDATION_ERROR");
        assertError(put("/api/feature-flags/booking.3d", "{\"enabled\":false,\"reason\":\"x\"}", jwt.generateAccessToken(this.owner, tenant, "MANAGER")), 403, "FORBIDDEN");
        ok(put("/api/feature-flags/booking.3d", "{\"enabled\":false,\"reason\":\"GPU sorunları: pilotta 3D kapalı\"}", owner));

        assertFalse(json.readTree(ok(get("/api/public/v1/shops/" + slug + "/features", null)).body()).get("threeD").asBoolean());
        assertError(get("/api/public/v1/shops/" + slug + "/scene", null), 409, "FEATURE_DISABLED");
        String startsAt = firstSlot();
        var customer = registerCustomer();
        var rejected = post("/api/customer/v1/booking-requests", """
                {"tenantSlug":"%s","serviceId":"%s","staffId":"%s","startsAt":"%s","source":"WEB_3D","sceneVersion":%d,"chairKey":"%s"}
                """.formatted(slug, service, staff, startsAt, version, chairKey), customer.token(), UUID.randomUUID().toString());
        assertError(rejected, 409, "FEATURE_DISABLED");
        assertEquals("WEB_LIST", json.readTree(rejected.body()).get("details").get("fallback").asString());
        // Aynı seçim liste kaynağıyla sorunsuz gönderilebilir.
        var listed = post("/api/customer/v1/booking-requests", """
                {"tenantSlug":"%s","serviceId":"%s","staffId":"%s","startsAt":"%s","source":"WEB_LIST"}
                """.formatted(slug, service, staff, startsAt), customer.token(), UUID.randomUUID().toString());
        assertEquals(201, listed.statusCode(), listed.body());
        assertEquals("WEB_LIST", db.queryForObject("select source from booking_requests where id=?", String.class,
                UUID.fromString(json.readTree(listed.body()).get("id").asString())));

        // Global anahtar kapalıyken işletme açamaz; geçersiz kılma kaldırılınca global değere dönülür.
        db.update("update feature_flags set enabled=false where flag_key='booking.3d' and tenant_id is null");
        try {
            ok(put("/api/feature-flags/booking.3d", "{\"enabled\":true,\"reason\":\"Tekrar dene\"}", owner));
            assertError(get("/api/public/v1/shops/" + slug + "/scene", null), 409, "FEATURE_DISABLED");
        } finally {
            db.update("update feature_flags set enabled=true where flag_key='booking.3d' and tenant_id is null");
        }
        ok(get("/api/public/v1/shops/" + slug + "/scene", null));
        assertTrue(db.queryForObject("select count(*) from audit_log where tenant_id=? and action='FEATURE_FLAG_CHANGED'", Long.class, tenant) >= 2);
    }

    @Test
    void pushSubscriptionsAreValidatedDeliveredEncryptedAndCleanedUpWhenGone() throws Exception {
        var config = json.readTree(ok(get("/api/public/v1/push/config", null)).body());
        assertTrue(config.get("enabled").asBoolean());
        assertEquals(87, config.get("publicKey").asString().length(), "65 baytlık P-256 anahtarı base64url ile 87 karakterdir");

        var customer = registerCustomer();
        var device = new WebPushTestClient("https://fcm.googleapis.com/fcm/send/gone-" + UUID.randomUUID());
        var secondDevice = new WebPushTestClient("https://fcm.googleapis.com/fcm/send/alive-" + UUID.randomUUID());
        assertError(post("/api/customer/v1/push-subscriptions",
                device.subscriptionJson().replace("https://fcm.googleapis.com", "https://169.254.169.254"), customer.token(), null), 400, "PUSH_ENDPOINT_NOT_ALLOWED");
        assertError(post("/api/customer/v1/push-subscriptions",
                device.subscriptionJson().replace(device.p256dh(), "BAAA"), customer.token(), null), 400, "INVALID_PUSH_SUBSCRIPTION");
        assertError(post("/api/push-subscriptions", device.subscriptionJson(), customer.token(), null), 401, "UNAUTHORIZED");
        assertEquals(204, post("/api/customer/v1/push-subscriptions", device.subscriptionJson(), customer.token(), null).statusCode());
        assertEquals(204, post("/api/customer/v1/push-subscriptions", device.subscriptionJson(), customer.token(), null).statusCode(), "tekrar kayıt idempotent");
        assertEquals(204, post("/api/customer/v1/push-subscriptions", secondDevice.subscriptionJson(), customer.token(), null).statusCode());
        assertEquals(2L, db.queryForObject("select count(*) from push_subscriptions where owner_id=? and disabled_at is null", Long.class, customer.id()));

        var test = json.readTree(ok(post("/api/customer/v1/push-subscriptions/test", "{}", customer.token(), null)).body());
        assertEquals(2, test.get("queued").asInt());
        assertEquals(0, json.readTree(ok(post("/api/customer/v1/push-subscriptions/test", "{}", customer.token(), null)).body()).get("queued").asInt(),
                "Test bildirimi dakikada bir ile sınırlı");

        pushTransport.respond(device.endpoint(), 410);
        pushWorker.processBatch();
        var alive = pushTransport.sentTo(secondDevice.endpoint());
        assertEquals(1, alive.size());
        assertEquals("86400", alive.getFirst().headers().get("TTL"));
        assertEquals("Bildirimler açık", json.readTree(secondDevice.decrypt(alive.getFirst().body())).get("title").asString());
        var gone = db.queryForMap("select disabled_at,disabled_reason from push_subscriptions where endpoint_hash=encode(sha256(convert_to(?,'UTF8')),'hex')", device.endpoint());
        assertNotNull(gone.get("disabled_at"));
        assertEquals("GONE_410", gone.get("disabled_reason"));

        // 429/5xx yeniden denenir, kalıcı değildir.
        pushTransport.respond(secondDevice.endpoint(), 503);
        db.update("delete from push_deliveries where subscription_id in (select id from push_subscriptions where owner_id=?)", customer.id());
        ok(post("/api/customer/v1/push-subscriptions/test", "{}", customer.token(), null));
        pushWorker.processBatch();
        var retry = db.queryForMap("select status,attempts,last_status,available_at>now() as later from push_deliveries d join push_subscriptions s on s.id=d.subscription_id where s.owner_id=? and s.disabled_at is null", customer.id());
        assertEquals("PENDING", retry.get("status"));
        assertEquals(1, retry.get("attempts"));
        assertEquals(503, retry.get("last_status"));
        assertEquals(true, retry.get("later"));

        ok(post("/api/customer/v1/push-subscriptions/unsubscribe", "{\"endpoint\":\"" + secondDevice.endpoint() + "\"}", customer.token(), null));
        assertEquals(0L, db.queryForObject("select count(*) from push_subscriptions where owner_id=? and disabled_at is null", Long.class, customer.id()));

        // İşletmede push kapalıysa bildirim kuyruğa alınmaz, abonelik kabul edilmez (personel).
        String owner = ownerToken();
        ok(put("/api/feature-flags/push.notifications", "{\"enabled\":false,\"reason\":\"Pilot öncesi\"}", owner));
        var staffDevice = new WebPushTestClient("https://fcm.googleapis.com/fcm/send/staff-" + UUID.randomUUID());
        assertError(post("/api/push-subscriptions", staffDevice.subscriptionJson(), owner, null), 409, "FEATURE_DISABLED");
    }

    @Test
    void logoutEverywhereRevokesRefreshTokensAndPushButAllowsFreshLogin() throws Exception {
        var customer = registerCustomer();
        var device = new WebPushTestClient("https://fcm.googleapis.com/fcm/send/logout-" + UUID.randomUUID());
        assertEquals(204, post("/api/customer/v1/push-subscriptions", device.subscriptionJson(), customer.token(), null).statusCode());
        String staffRefresh = jwt.generateRefreshToken(owner, tenant, "OWNER");
        Thread.sleep(1100); // iat saniye hassasiyetindedir; iptal anından önce üretilmiş token'lar hedeflenir.

        assertEquals(204, post("/api/customer/v1/auth/logout-all", "{}", customer.token(), null).statusCode());
        assertError(post("/api/customer/v1/auth/refresh", "{\"refreshToken\":\"" + customer.refresh() + "\"}", null, null), 401, "INVALID_CUSTOMER_REFRESH_TOKEN");
        assertEquals("LOGOUT_ALL", db.queryForObject("select disabled_reason from push_subscriptions where owner_id=?", String.class, customer.id()));
        var fresh = post("/api/customer/v1/auth/login", "{\"email\":\"" + customer.email() + "\",\"password\":\"secret123\"}", null, null);
        assertEquals(200, fresh.statusCode(), fresh.body());
        ok(post("/api/customer/v1/auth/refresh", "{\"refreshToken\":\"" + json.readTree(fresh.body()).get("refreshToken").asString() + "\"}", null, null));

        assertEquals(204, post("/api/auth/logout-all", "{}", ownerToken(), null).statusCode());
        assertError(post("/api/auth/refresh", "{\"refreshToken\":\"" + staffRefresh + "\"}", null, null), 401, "INVALID_REFRESH_TOKEN");
        assertError(post("/api/auth/logout-all", "{}", null, null), 401, "UNAUTHORIZED");
    }

    @Test
    void retentionPurgesOnlyExpiredOperationalDataAndRecordsTheRun() throws Exception {
        var customer = registerCustomer();
        UUID tenantCustomer = UUID.randomUUID();
        db.update("insert into tenant_customers(id,tenant_id,customer_account_id) values (?,?,?)", tenantCustomer, tenant, customer.id());
        db.update("insert into idempotency_keys(tenant_id,user_id,key,request_hash,response,created_at) values (?,?,?,?,?,now()-interval '3 days')", tenant, owner, "old", "h", "{}");
        db.update("insert into idempotency_keys(tenant_id,user_id,key,request_hash,response,created_at) values (?,?,?,?,?,now()-interval '1 hour')", tenant, owner, "fresh", "h", "{}");
        db.update("insert into in_app_notifications(tenant_id,recipient_type,recipient_id,event_type,title,body,read_at,created_at) values (?,'STAFF',?,'X','t','b',now()-interval '100 days',now()-interval '100 days')", tenant, owner);
        db.update("insert into in_app_notifications(tenant_id,recipient_type,recipient_id,event_type,title,body,created_at) values (?,'STAFF',?,'Y','t','b',now()-interval '100 days')", tenant, owner);
        db.update("insert into in_app_notifications(tenant_id,recipient_type,recipient_id,event_type,title,body,created_at) values (?,'STAFF',?,'Z','t','b',now()-interval '200 days')", tenant, owner);

        var deleted = retention.run();
        assertTrue(deleted.get("idempotency_keys") >= 1);
        assertEquals(java.util.List.of("fresh"), db.queryForList("select key from idempotency_keys where tenant_id=?", String.class, tenant));
        assertEquals(java.util.List.of("Y"), db.queryForList("select event_type from in_app_notifications where tenant_id=? order by event_type", String.class, tenant),
                "Okunmuş 90+ gün ve her durumda 180+ gün silinir; okunmamış 100 günlük kalır");
        assertTrue(db.queryForObject("select count(*) from data_retention_runs where finished_at>now()-interval '1 minute'", Long.class) >= 1);
    }

    @Test
    void operationalMetricsRequireTheOpsTokenAndExposePhase8Meters() throws Exception {
        assertEquals(200, get("/actuator/health", null).statusCode());
        assertEquals(401, get("/actuator/metrics", null).statusCode());
        assertEquals(401, get("/actuator/metrics", ownerToken()).statusCode(), "Kullanıcı JWT'si metriklere erişemez");
        assertEquals(401, get("/actuator/metrics", "wrong-token").statusCode());
        var customer = registerCustomer();
        post("/api/customer/v1/booking-requests", "{\"tenantSlug\":\"%s\",\"serviceId\":\"%s\",\"staffId\":\"%s\",\"startsAt\":\"%s\"}"
                .formatted(slug, service, staff, firstSlot()), customer.token(), UUID.randomUUID().toString());
        var names = json.readTree(ok(get("/actuator/metrics", "phase8-ops-token")).body()).get("names").toString();
        assertTrue(names.contains("saas.booking.decisions"), names);
        assertTrue(names.contains("saas.queue.pending"), names);
        assertTrue(names.contains("http.server.requests"), names);
        var depth = db.queryForList("select queue_name from ops_queue_depth()", String.class);
        assertEquals(java.util.List.of("outbox", "push", "booking_approval"), depth);
    }

    private String firstSlot() throws Exception {
        var body = json.readTree(ok(get("/api/public/v1/shops/" + slug + "/availability?serviceId=" + service + "&staffId=" + staff + "&date=" + date, null)).body());
        return body.get("slots").get(0).get("startsAt").asString();
    }

    private String ownerToken() { return jwt.generateAccessToken(owner, tenant, "OWNER"); }

    private Customer registerCustomer() throws Exception {
        String email = "phase8-" + UUID.randomUUID() + "@example.test";
        var response = post("/api/customer/v1/auth/register", "{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"Pilot Müşteri\"}", null, null);
        assertEquals(201, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        return new Customer(UUID.fromString(body.get("customer").get("id").asString()), email,
                body.get("accessToken").asString(), body.get("refreshToken").asString());
    }

    private HttpResponse<String> ok(HttpResponse<String> response) {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.statusCode() + " " + response.body());
        return response;
    }

    private HttpResponse<String> get(String path, String token) throws Exception { return send("GET", path, null, token, null); }
    private HttpResponse<String> post(String path, String body, String token, String key) throws Exception { return send("POST", path, body, token, key); }
    private HttpResponse<String> put(String path, String body, String token) throws Exception { return send("PUT", path, body, token, null); }

    private HttpResponse<String> send(String method, String path, String body, String token, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).get("code").asString(), response.body());
    }

    private record Customer(UUID id, String email, String token, String refresh) {}
}
