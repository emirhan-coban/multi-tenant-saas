package com.saas.backend.booking;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerBookingIntegrationTests extends PostgresTestBase {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired StaffBookingService staffBookings;
    @Autowired StaffRescheduleService staffReschedules;
    @Autowired CalendarClosureService closures;
    @Autowired BookingSettingsService settings;
    @Autowired ShopSceneService scenes;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private Shop shop;

    @BeforeEach
    void setup() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHDB");
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), staff = UUID.randomUUID(), service = UUID.randomUUID();
        String slug = "booking-" + tenant;
        db.update("insert into tenants(id,name,slug,timezone,plan) values (?,?,?,?,?)", tenant, "Moda Berber", slug, "Europe/Istanbul", "PRO");
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", user, user + "@example.test", "hash", "Deniz Usta");
        db.update("insert into memberships(user_id,tenant_id,role) values (?,?,'OWNER')", user, tenant);
        db.update("insert into staff(id,tenant_id,user_id,display_name,chair_key) values (?,?,?,?,?)", staff, tenant, user, "Deniz Usta", "window-chair");
        db.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,?,?,?)", service, tenant, "Saç Kesimi", 30, 10, 450);
        var date = LocalDate.now(ZoneId.of("Europe/Istanbul")).plusDays(2);
        db.update("insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (?,?,?,?,?)",
                tenant, staff, date.getDayOfWeek().getValue() - 1, LocalTime.of(9, 0), LocalTime.of(18, 0));
        shop = new Shop(tenant, slug, user, staff, service, date);
    }

    @Test
    void publicDiscoveryNewCustomerHoldExpiryAndIdempotencyWorkEndToEnd() throws Exception {
        var publicShop = get("/api/public/v1/shops/" + shop.slug(), null);
        assertEquals(200, publicShop.statusCode(), publicShop.body());
        var shopBody = json.readTree(publicShop.body());
        assertEquals("Moda Berber", shopBody.get("name").asString());
        assertEquals("window-chair", shopBody.get("staff").get(0).get("chairKey").asString());

        var available = availability();
        String startsAt = available.get("slots").get(0).get("startsAt").asString();
        var customer = register("new-" + UUID.randomUUID() + "@example.test");
        String key = UUID.randomUUID().toString();
        String body = requestBody(startsAt, null);

        var created = post("/api/customer/v1/booking-requests", body, customer.token(), key);
        assertEquals(201, created.statusCode(), created.body());
        var request = json.readTree(created.body());
        assertEquals("PENDING_APPROVAL", request.get("status").asString());
        assertEquals("NEW", request.get("trustState").asString());
        assertFalse(request.get("holdExpiresAt").isNull());

        var replay = post("/api/customer/v1/booking-requests", body, customer.token(), key);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(request.get("id").asString(), json.readTree(replay.body()).get("id").asString());
        assertError(post("/api/customer/v1/booking-requests", requestBody(startsAt, "changed"), customer.token(), key), 409, "IDEMPOTENCY_CONFLICT");

        assertEquals("REQUEST", request.get("resultType").asString());
        assertTrue(containsSlot(availability(), startsAt));
        UUID requestId = UUID.fromString(request.get("id").asString());
        db.update("update booking_requests set hold_expires_at=now()-interval '1 second' where id=?", requestId);
        var expired = get("/api/customer/v1/booking-requests/" + requestId, customer.token());
        assertEquals("EXPIRED", json.readTree(expired.body()).get("status").asString());
        assertTrue(containsSlot(availability(), startsAt));
    }

    @Test
    void publishedChairRoutesNewAndTrustedCustomersAndRejectsAStaleScene() throws Exception {
        var manifestResponse = get("/api/public/v1/shops/" + shop.slug() + "/scene", null);
        assertEquals(200, manifestResponse.statusCode(), manifestResponse.body());
        var manifest = json.readTree(manifestResponse.body());
        assertEquals(1, manifest.get("version").asInt());
        assertEquals(shop.staff().toString(), manifest.get("chairs").get(0).get("staffId").asString());
        String chairKey = manifest.get("chairs").get(0).get("chairKey").asString();
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();

        var newcomer = register("scene-new-" + UUID.randomUUID() + "@example.test");
        String sceneBody = sceneRequestBody(startsAt, 1, chairKey);
        var pending = post("/api/customer/v1/booking-requests", sceneBody, newcomer.token(), UUID.randomUUID().toString());
        assertEquals(201, pending.statusCode(), pending.body());
        assertEquals("PENDING_APPROVAL", json.readTree(pending.body()).get("status").asString());

        var trusted = register("scene-trusted-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')", shop.tenant(), trusted.id());
        var confirmed = post("/api/customer/v1/booking-requests", sceneBody, trusted.token(), UUID.randomUUID().toString());
        assertEquals(201, confirmed.statusCode(), confirmed.body());
        UUID appointment = UUID.fromString(json.readTree(confirmed.body()).get("appointmentId").asString());
        assertEquals("WEB_3D", db.queryForObject("select source from booking_requests where id=?", String.class,
                UUID.fromString(json.readTree(confirmed.body()).get("id").asString())));
        assertEquals("WEB_3D", db.queryForObject("select source from appointments where id=?", String.class, appointment));

        var owner = new AuthenticatedUser(shop.user(), shop.tenant(), "OWNER");
        var state = scenes.get(owner);
        var published = scenes.publish(owner, new ShopSceneService.Publish(state.draft().revision()));
        assertEquals(2, published.published().version());

        var staleCustomer = register("scene-stale-" + UUID.randomUUID() + "@example.test");
        assertError(post("/api/customer/v1/booking-requests", sceneBody, staleCustomer.token(), UUID.randomUUID().toString()),
                409, "STALE_SCENE_SELECTION");

        var rolledBack = scenes.rollback(owner, new ShopSceneService.Rollback(1, published.draft().revision()));
        assertEquals(3, rolledBack.published().version());
        assertEquals(chairKey, rolledBack.published().chairs().getFirst().chairKey());
        assertTrue(rolledBack.history().contains(1));
    }

    @Test
    void trustedCustomerIsAutoConfirmedWithPriceAndContactSnapshot() throws Exception {
        var customer = register("trusted-" + UUID.randomUUID() + "@example.test");
        UUID tenantCustomer = UUID.randomUUID();
        db.update("insert into tenant_customers(id,tenant_id,customer_account_id,trust_state) values (?,?,?,'TRUSTED')",
                tenantCustomer, shop.tenant(), customer.id());
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();

        var created = post("/api/customer/v1/booking-requests", requestBody(startsAt, null), customer.token(), UUID.randomUUID().toString());
        assertEquals(201, created.statusCode(), created.body());
        var result = json.readTree(created.body());
        assertEquals("AUTO_CONFIRMED", result.get("status").asString());
        assertEquals("AUTO_CONFIRM", result.get("decision").asString());
        assertEquals("APPOINTMENT", result.get("resultType").asString());
        assertFalse(result.get("appointmentId").isNull());

        var appointment = db.queryForMap("select customer_id,customer_name,customer_email,price_amount,duration_min,buffer_min from appointments where id=?",
                UUID.fromString(result.get("appointmentId").asString()));
        assertEquals(tenantCustomer, appointment.get("customer_id"));
        assertEquals("Web Customer", appointment.get("customer_name"));
        assertEquals("450.00", appointment.get("price_amount").toString());
        assertEquals(30, appointment.get("duration_min"));
        assertEquals(10, appointment.get("buffer_min"));
    }

    @Test
    void concurrentNewCustomerRequestsCanShareARequestedSlotWithoutBlockingIt() throws Exception {
        var first = register("race-a-" + UUID.randomUUID() + "@example.test");
        var second = register("race-b-" + UUID.randomUUID() + "@example.test");
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();
        String body = requestBody(startsAt, null);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> post("/api/customer/v1/booking-requests", body, first.token(), UUID.randomUUID().toString()));
            var two = executor.submit(() -> post("/api/customer/v1/booking-requests", body, second.token(), UUID.randomUUID().toString()));
            var a = one.get();
            var b = two.get();
            assertEquals(2, java.util.stream.Stream.of(a, b).filter(response -> response.statusCode() == 201).count(), a.body() + b.body());
            assertEquals("PENDING_APPROVAL", json.readTree(a.body()).get("status").asString());
            assertEquals("PENDING_APPROVAL", json.readTree(b.body()).get("status").asString());
        }
        assertTrue(containsSlot(availability(), startsAt));
    }

    @Test
    void trustedCustomerCanTakeARequestedSlotAndPendingApprovalRechecksAvailability() throws Exception {
        var newCustomer = register("pending-slot-" + UUID.randomUUID() + "@example.test");
        var trustedCustomer = register("trusted-slot-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')",
                shop.tenant(), trustedCustomer.id());
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();

        var pending = post("/api/customer/v1/booking-requests", requestBody(startsAt, null),
                newCustomer.token(), UUID.randomUUID().toString());
        UUID pendingId = UUID.fromString(json.readTree(pending.body()).get("id").asString());
        assertEquals("REQUEST", json.readTree(pending.body()).get("resultType").asString());

        var confirmed = post("/api/customer/v1/booking-requests", requestBody(startsAt, null),
                trustedCustomer.token(), UUID.randomUUID().toString());
        assertEquals(201, confirmed.statusCode(), confirmed.body());
        assertEquals("APPOINTMENT", json.readTree(confirmed.body()).get("resultType").asString());

        var error = assertThrows(ApiException.class, () -> staffBookings.approve(
                new AuthenticatedUser(shop.user(), shop.tenant(), "OWNER"), pendingId, UUID.randomUUID().toString()));
        assertEquals("SLOT_UNAVAILABLE", error.getCode());
        assertEquals("PENDING_APPROVAL", db.queryForObject(
                "select status from booking_requests where id=?", String.class, pendingId));
        assertEquals(1L, db.queryForObject(
                "select count(*) from appointments where tenant_id=?", Long.class, shop.tenant()));
    }

    @Test
    void tenantPolicyCanRouteTrustedCustomerBackToApproval() throws Exception {
        var customer = register("policy-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')", shop.tenant(), customer.id());
        db.update("update booking_settings set trusted_auto_confirm_enabled=false where tenant_id=?", shop.tenant());
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();

        var result = post("/api/customer/v1/booking-requests", requestBody(startsAt, null), customer.token(), UUID.randomUUID().toString());
        assertEquals(201, result.statusCode(), result.body());
        assertEquals("REQUEST", json.readTree(result.body()).get("resultType").asString());
        assertEquals("PENDING_APPROVAL", json.readTree(result.body()).get("status").asString());
        assertEquals(0L, db.queryForObject("select count(*) from appointments where tenant_id=?", Long.class, shop.tenant()));
    }

    @Test
    void trustedCustomerLimitsFutureBookingsAndCannotOverlapAcrossBarbers() throws Exception {
        var customer = register("limits-" + UUID.randomUUID() + "@example.test");
        UUID tenantCustomer = UUID.randomUUID();
        db.update("insert into tenant_customers(id,tenant_id,customer_account_id,trust_state) values (?,?,?,'TRUSTED')", tenantCustomer, shop.tenant(), customer.id());
        UUID otherUser = UUID.randomUUID(), otherStaff = UUID.randomUUID();
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", otherUser, otherUser + "@example.test", "hash", "Ece Usta");
        db.update("insert into staff(id,tenant_id,user_id,display_name,chair_key) values (?,?,?,?,?)", otherStaff, shop.tenant(), otherUser, "Ece Usta", "door-chair");
        db.update("insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (?,?,?,?,?)",
                shop.tenant(), otherStaff, shop.date().getDayOfWeek().getValue() - 1, LocalTime.of(9, 0), LocalTime.of(18, 0));
        String firstSlot = availability().get("slots").get(0).get("startsAt").asString();
        assertEquals(201, post("/api/customer/v1/booking-requests", requestBody(firstSlot, null), customer.token(), UUID.randomUUID().toString()).statusCode());

        String overlapping = requestBody(firstSlot, null).replace(shop.staff().toString(), otherStaff.toString());
        assertError(post("/api/customer/v1/booking-requests", overlapping, customer.token(), UUID.randomUUID().toString()), 409, "CUSTOMER_TIME_CONFLICT");

        String secondSlot = availability().get("slots").get(0).get("startsAt").asString();
        assertEquals(201, post("/api/customer/v1/booking-requests", requestBody(secondSlot, null), customer.token(), UUID.randomUUID().toString()).statusCode());
        String thirdSlot = availability().get("slots").get(0).get("startsAt").asString();
        assertError(post("/api/customer/v1/booking-requests", requestBody(thirdSlot, null), customer.token(), UUID.randomUUID().toString()), 409, "FUTURE_APPOINTMENT_LIMIT");
    }

    @Test
    void dailyLimitCapsNewBookingOperationsIndependentlyOfFutureLimit() throws Exception {
        var customer = register("daily-limit-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')", shop.tenant(), customer.id());
        db.update("update booking_settings set max_future_appointments=10,max_daily_bookings=2 where tenant_id=?", shop.tenant());

        String first = availability().get("slots").get(0).get("startsAt").asString();
        assertEquals(201, post("/api/customer/v1/booking-requests", requestBody(first, null), customer.token(), UUID.randomUUID().toString()).statusCode());
        String second = availability().get("slots").get(0).get("startsAt").asString();
        assertEquals(201, post("/api/customer/v1/booking-requests", requestBody(second, null), customer.token(), UUID.randomUUID().toString()).statusCode());
        String third = availability().get("slots").get(0).get("startsAt").asString();
        assertError(post("/api/customer/v1/booking-requests", requestBody(third, null), customer.token(), UUID.randomUUID().toString()), 409, "DAILY_BOOKING_LIMIT");
    }

    @Test
    void trustRevocationAndAutoBookingSerializeWithoutUnsafeConfirmation() throws Exception {
        var customer = register("trust-race-" + UUID.randomUUID() + "@example.test");
        UUID tenantCustomer = UUID.randomUUID();
        db.update("insert into tenant_customers(id,tenant_id,customer_account_id,trust_state) values (?,?,?,'TRUSTED')", tenantCustomer, shop.tenant(), customer.id());
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var booking = executor.submit(() -> post("/api/customer/v1/booking-requests", requestBody(startsAt, null), customer.token(), UUID.randomUUID().toString()));
            var revoke = executor.submit(() -> staffBookings.updateTrust(
                    new AuthenticatedUser(shop.user(), shop.tenant(), "OWNER"), tenantCustomer,
                    new BookingDtos.TrustUpdate(CustomerTrustState.REVIEW_REQUIRED, 0, "Eşzamanlı güven incelemesi")));
            var response = booking.get();
            revoke.get();
            assertEquals(201, response.statusCode(), response.body());
            String status = json.readTree(response.body()).get("status").asString();
            assertTrue(status.equals("AUTO_CONFIRMED") || status.equals("PENDING_APPROVAL"), response.body());
            assertEquals("REVIEW_REQUIRED", db.queryForObject("select trust_state from tenant_customers where id=?", String.class, tenantCustomer));
            long appointments = db.queryForObject("select count(*) from appointments where customer_id=?", Long.class, tenantCustomer);
            assertEquals(status.equals("AUTO_CONFIRMED") ? 1L : 0L, appointments);
        }
    }

    @Test
    void trustedCustomerReschedulesAtomicallyAndStaleReplayCannotMoveItAgain() throws Exception {
        var customer = register("move-trusted-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')", shop.tenant(), customer.id());
        String first = availability().get("slots").get(0).get("startsAt").asString();
        var created = json.readTree(post("/api/customer/v1/booking-requests", requestBody(first, null), customer.token(), UUID.randomUUID().toString()).body());
        String target = availability().get("slots").get(0).get("startsAt").asString();
        String command = rescheduleBody(created, target);
        String key = UUID.randomUUID().toString();

        var moved = post("/api/customer/v1/booking-requests/" + created.get("id").asString() + "/reschedule", command, customer.token(), key);
        assertEquals(200, moved.statusCode(), moved.body());
        var result = json.readTree(moved.body());
        assertEquals(target, result.get("startsAt").asString());
        assertEquals("APPROVED", result.get("rescheduleStatus").asString());
        assertEquals(target, db.queryForObject("select starts_at::text from appointments where id=?", String.class,
                UUID.fromString(created.get("appointmentId").asString())).replace(" ", "T").replace("+00", "Z"));

        var replay = post("/api/customer/v1/booking-requests/" + created.get("id").asString() + "/reschedule", command, customer.token(), key);
        assertEquals(200, replay.statusCode(), replay.body());
        String changedCommand = rescheduleBody(created, Instant.parse(target).plusSeconds(1800).toString());
        assertError(post("/api/customer/v1/booking-requests/" + created.get("id").asString() + "/reschedule",
                changedCommand, customer.token(), key), 409, "IDEMPOTENCY_CONFLICT");
        assertError(post("/api/customer/v1/booking-requests/" + created.get("id").asString() + "/reschedule",
                command, customer.token(), UUID.randomUUID().toString()), 409, "STALE_VERSION");
    }

    @Test
    void reviewedCustomerRescheduleKeepsOldAppointmentWhenRequestedSlotIsTaken() throws Exception {
        var customer = register("move-review-" + UUID.randomUUID() + "@example.test");
        UUID tenantCustomer = UUID.randomUUID();
        db.update("insert into tenant_customers(id,tenant_id,customer_account_id,trust_state) values (?,?,?,'TRUSTED')",
                tenantCustomer, shop.tenant(), customer.id());
        String original = availability().get("slots").get(0).get("startsAt").asString();
        var created = json.readTree(post("/api/customer/v1/booking-requests", requestBody(original, null), customer.token(), UUID.randomUUID().toString()).body());
        db.update("update tenant_customers set trust_state='REVIEW_REQUIRED',version=version+1 where id=?", tenantCustomer);
        String target = availability().get("slots").get(0).get("startsAt").asString();

        var requested = post("/api/customer/v1/booking-requests/" + created.get("id").asString() + "/reschedule",
                rescheduleBody(created, target), customer.token(), UUID.randomUUID().toString());
        assertEquals(200, requested.statusCode(), requested.body());
        var requestedBody = json.readTree(requested.body());
        assertEquals("PENDING_APPROVAL", requestedBody.get("rescheduleStatus").asString());
        assertEquals(original, requestedBody.get("startsAt").asString());

        var other = register("move-other-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')", shop.tenant(), other.id());
        assertEquals(201, post("/api/customer/v1/booking-requests", requestBody(target, null), other.token(), UUID.randomUUID().toString()).statusCode());

        UUID rescheduleId = UUID.fromString(requestedBody.get("rescheduleRequestId").asString());
        var decision = staffReschedules.approve(new AuthenticatedUser(shop.user(), shop.tenant(), "OWNER"),
                rescheduleId, UUID.randomUUID().toString());
        assertEquals("NEEDS_RESELECTION", decision.status());
        assertEquals(java.sql.Timestamp.from(Instant.parse(original)), db.queryForObject(
                "select starts_at from appointments where id=?", java.sql.Timestamp.class,
                UUID.fromString(created.get("appointmentId").asString())));
        assertTrue(db.queryForObject("select count(*) from in_app_notifications where recipient_type='CUSTOMER' and recipient_id=?",
                Long.class, customer.id()) > 0);
    }

    @Test
    void closurePreviewProtectsAppointmentsAndCreatedBreakRemovesAvailability() throws Exception {
        var owner = new AuthenticatedUser(shop.user(), shop.tenant(), "OWNER");
        String first = availability().get("slots").get(0).get("startsAt").asString();
        Instant starts = Instant.parse(first);
        var breakRequest = new CalendarClosureService.Request(shop.staff(), CalendarClosureService.Type.BREAK,
                starts, starts.plus(Duration.ofMinutes(30)), "Öğle molası");
        var created = closures.create(owner, breakRequest);
        assertEquals(CalendarClosureService.Type.BREAK, created.type());
        assertFalse(containsSlot(availability(), first));

        var customer = register("closure-conflict-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')", shop.tenant(), customer.id());
        String occupied = availability().get("slots").get(0).get("startsAt").asString();
        assertEquals(201, post("/api/customer/v1/booking-requests", requestBody(occupied, null), customer.token(), UUID.randomUUID().toString()).statusCode());
        Instant occupiedAt = Instant.parse(occupied);
        var conflict = new CalendarClosureService.Request(shop.staff(), CalendarClosureService.Type.BREAK,
                occupiedAt, occupiedAt.plus(Duration.ofMinutes(30)), "Acil mola");
        assertEquals(1, closures.preview(owner, conflict).appointments().size());
        assertEquals("CLOSURE_CONFLICT", assertThrows(ApiException.class, () -> closures.create(owner, conflict)).getCode());
    }

    @Test
    void bookingSettingsUseOptimisticVersioning() {
        var owner = new AuthenticatedUser(shop.user(), shop.tenant(), "OWNER");
        var current = settings.get(owner);
        var updated = settings.update(owner, new BookingSettingsService.Update(false, 4, 5, 45, 90, 30, 180, current.version()));
        assertFalse(updated.trustedAutoConfirmEnabled());
        assertEquals(180, updated.cancellationCutoffMinutes());
        assertEquals("STALE_VERSION", assertThrows(ApiException.class, () -> settings.update(owner,
                new BookingSettingsService.Update(true, 2, 3, 30, 60, 15, 120, current.version()))).getCode());
    }

    @Test
    void customerCanCancelPendingRequestAndReplayTheSameCommand() throws Exception {
        var customer = register("cancel-pending-" + UUID.randomUUID() + "@example.test");
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();
        var created = post("/api/customer/v1/booking-requests", requestBody(startsAt, null), customer.token(), UUID.randomUUID().toString());
        var createdBody = json.readTree(created.body());
        UUID requestId = UUID.fromString(createdBody.get("id").asString());
        String key = UUID.randomUUID().toString();
        String versions = actionVersions(createdBody);

        var cancelled = post("/api/customer/v1/booking-requests/" + requestId + "/cancel", versions, customer.token(), key);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("CANCELLED", json.readTree(cancelled.body()).get("status").asString());
        assertTrue(json.readTree(cancelled.body()).get("cancelledAt").isTextual());
        assertTrue(containsSlot(availability(), startsAt));

        var replay = post("/api/customer/v1/booking-requests/" + requestId + "/cancel", versions, customer.token(), key);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(requestId.toString(), json.readTree(replay.body()).get("id").asString());
        assertError(post("/api/customer/v1/booking-requests/" + requestId + "/cancel", versions, customer.token(), UUID.randomUUID().toString()),
                409, "BOOKING_ALREADY_CANCELLED");

        var account = get("/api/customer/v1/booking-requests", customer.token());
        assertEquals(200, account.statusCode(), account.body());
        assertEquals(1, json.readTree(account.body()).get("summary").get("cancelled").asInt());
        assertEquals("Moda Berber", json.readTree(account.body()).get("items").get(0).get("tenantName").asString());
    }

    @Test
    void trustedCustomerCancellationUpdatesAppointmentAuditAndOwnershipIsEnforced() throws Exception {
        var customer = register("cancel-confirmed-" + UUID.randomUUID() + "@example.test");
        var stranger = register("cancel-stranger-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')",
                shop.tenant(), customer.id());
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();
        var created = post("/api/customer/v1/booking-requests", requestBody(startsAt, null), customer.token(), UUID.randomUUID().toString());
        var body = json.readTree(created.body());
        UUID requestId = UUID.fromString(body.get("id").asString());
        UUID appointmentId = UUID.fromString(body.get("appointmentId").asString());

        String versions = actionVersions(body);
        assertError(post("/api/customer/v1/booking-requests/" + requestId + "/cancel", versions, stranger.token(), UUID.randomUUID().toString()),
                404, "NOT_FOUND");
        var cancelled = post("/api/customer/v1/booking-requests/" + requestId + "/cancel", versions, customer.token(), UUID.randomUUID().toString());
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("CANCELLED", db.queryForObject("select status from appointments where id=?", String.class, appointmentId));
        assertEquals("CUSTOMER", db.queryForObject("select cancellation_source from booking_requests where id=?", String.class, requestId));
        assertEquals(customer.id(), db.queryForObject("select customer_actor_id from audit_log where entity_id=? and action='APPOINTMENT_CANCELLED'", UUID.class, appointmentId));
        assertTrue(containsSlot(availability(), startsAt));
    }

    @Test
    void onlineCancellationUsesTenantCutoffBeforeAppointment() throws Exception {
        var customer = register("cancel-late-" + UUID.randomUUID() + "@example.test");
        db.update("insert into tenant_customers(tenant_id,customer_account_id,trust_state) values (?,?,'TRUSTED')",
                shop.tenant(), customer.id());
        String startsAt = availability().get("slots").get(0).get("startsAt").asString();
        var created = post("/api/customer/v1/booking-requests", requestBody(startsAt, null), customer.token(), UUID.randomUUID().toString());
        var body = json.readTree(created.body());
        UUID requestId = UUID.fromString(body.get("id").asString());
        UUID appointmentId = UUID.fromString(body.get("appointmentId").asString());
        Instant near = Instant.now().plus(Duration.ofMinutes(30));
        db.update("update appointments set starts_at=?,ends_at=?,busy_until=? where id=?", java.sql.Timestamp.from(near),
                java.sql.Timestamp.from(near.plusSeconds(1800)), java.sql.Timestamp.from(near.plusSeconds(2400)), appointmentId);
        db.update("update booking_requests set starts_at=?,busy_until=? where id=?", java.sql.Timestamp.from(near),
                java.sql.Timestamp.from(near.plusSeconds(2400)), requestId);

        assertError(post("/api/customer/v1/booking-requests/" + requestId + "/cancel", actionVersions(body), customer.token(), UUID.randomUUID().toString()),
                409, "CANCELLATION_WINDOW_CLOSED");
        assertEquals("CONFIRMED", db.queryForObject("select status from appointments where id=?", String.class, appointmentId));
    }

    private JsonNode availability() throws Exception {
        String path = "/api/public/v1/shops/" + shop.slug() + "/availability?serviceId=" + shop.service()
                + "&staffId=" + shop.staff() + "&date=" + shop.date();
        var response = get(path, null);
        assertEquals(200, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertTrue(body.get("slots").size() > 0, response.body());
        return body;
    }

    private boolean containsSlot(JsonNode availability, String startsAt) {
        for (var slot : availability.get("slots")) if (slot.get("startsAt").asString().equals(startsAt)) return true;
        return false;
    }

    private Customer register(String email) throws Exception {
        var response = post("/api/customer/v1/auth/register", """
                {"email":"%s","password":"secret123","fullName":"Web Customer","phone":"+90 555 111 22 33"}
                """.formatted(email), null, null);
        assertEquals(201, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        return new Customer(UUID.fromString(body.get("customer").get("id").asString()), body.get("accessToken").asString());
    }

    private String requestBody(String startsAt, String note) {
        return """
                {"tenantSlug":"%s","serviceId":"%s","staffId":"%s","startsAt":"%s","note":%s}
                """.formatted(shop.slug(), shop.service(), shop.staff(), startsAt, note == null ? "null" : "\"" + note + "\"");
    }

    private String sceneRequestBody(String startsAt, int version, String chairKey) {
        return """
                {"tenantSlug":"%s","serviceId":"%s","staffId":"%s","startsAt":"%s","note":null,
                 "source":"WEB_3D","sceneVersion":%d,"chairKey":"%s"}
                """.formatted(shop.slug(), shop.service(), shop.staff(), startsAt, version, chairKey);
    }

    private String actionVersions(JsonNode booking) {
        String appointmentVersion = booking.get("appointmentVersion").isNull() ? "null" : booking.get("appointmentVersion").asText();
        return "{\"version\":" + booking.get("version").asText() + ",\"appointmentVersion\":" + appointmentVersion + "}";
    }

    private String rescheduleBody(JsonNode booking, String startsAt) {
        return "{\"startsAt\":\"" + startsAt + "\",\"version\":" + booking.get("version").asText()
                + ",\"appointmentVersion\":" + booking.get("appointmentVersion").asText() + "}";
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).get("code").asString(), response.body());
    }

    private record Shop(UUID tenant, String slug, UUID user, UUID staff, UUID service, LocalDate date) {}
    private record Customer(UUID id, String token) {}
}
