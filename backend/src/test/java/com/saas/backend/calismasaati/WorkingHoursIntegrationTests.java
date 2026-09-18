package com.saas.backend.calismasaati;

import com.saas.backend.auth.ApiException;
import com.saas.backend.auth.JwtService;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.personel.Personel;
import com.saas.backend.personel.PersonelRepository;
import com.saas.backend.tenant.Tenant;
import com.saas.backend.tenant.TenantRepository;
import com.saas.backend.user.User;
import com.saas.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

// Shared Testcontainers PostgreSQL; see README for the command.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkingHoursIntegrationTests extends com.saas.backend.PostgresTestBase {
    @Autowired CalismaSaatiService service;
    @Autowired PersonelRepository staff;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    // Faz 9: fixture SQL'i şema sahibiyle çalışır; uygulama kodu kısıtlı saas_app rolündedir.
    final JdbcTemplate jdbc = ownerJdbc();
    @Value("${local.server.port}") int port;
    AuthenticatedUser owner;
    UUID personId;

    @BeforeEach void setup() {
        // Faz 9: test verisi şema sahibiyle kurulur; uygulama kodu kısıtlı rolde RLS'e tabidir.
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        personId = UUID.randomUUID();
        jdbc.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)", tenant, "Hours QA", tenant.toString(), "Europe/Istanbul");
        jdbc.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", user, user + "@example.test", "unused", "QA");
        jdbc.update("insert into staff(id,tenant_id,user_id,display_name) values (?,?,?,?)", personId, tenant, user, "QA Staff");
        owner = new AuthenticatedUser(user, tenant, "OWNER");
    }

    CalismaSaatiDtos.HaftalikRequest week(int start, int openDays) {
        return new CalismaSaatiDtos.HaftalikRequest(personId, IntStream.range(0, 7)
                .mapToObj(day -> new CalismaSaatiDtos.GunRequest(day, day >= openDays,
                        day >= openDays ? null : LocalTime.of(start, 0), day >= openDays ? null : LocalTime.of(18, 0))).toList());
    }

    @Test void repeatedSaveReplacesWeekAndClosedDaysRemoveRows() {
        service.haftayiKaydet(owner, week(9, 5));
        service.haftayiKaydet(owner, week(10, 5));
        var saved = service.listele(owner, personId);
        assertEquals(5, saved.size());
        assertTrue(saved.stream().allMatch(row -> row.startTime().equals(LocalTime.of(10, 0))));
        service.haftayiKaydet(owner, week(9, 0));
        assertTrue(service.listele(owner, personId).isEmpty());
    }

    @Test void duplicateDaysAndInvalidRangesLeaveOriginalWeekIntact() {
        service.haftayiKaydet(owner, week(9, 5));
        var days = new ArrayList<>(week(10, 5).days()); days.set(6, days.get(0));
        assertThrows(ApiException.class, () -> service.haftayiKaydet(owner, new CalismaSaatiDtos.HaftalikRequest(personId, days)));
        assertThrows(ApiException.class, () -> service.haftayiKaydet(owner, week(19, 5)));
        assertEquals(LocalTime.of(9, 0), service.listele(owner, personId).getFirst().startTime());
        assertEquals(5, service.listele(owner, personId).size());
    }

    @Test void databaseFailureAfterDeleteRollsBackWholeWeek() {
        service.haftayiKaydet(owner, week(9, 5));
        jdbc.execute("alter table working_hours add constraint qa_reject_hour check (start_time <> time '03:00')");
        try {
            assertThrows(RuntimeException.class, () -> service.haftayiKaydet(owner, week(3, 5)));
            var saved = service.listele(owner, personId);
            assertEquals(5, saved.size());
            assertTrue(saved.stream().allMatch(row -> row.startTime().equals(LocalTime.of(9, 0))));
        } finally { jdbc.execute("alter table working_hours drop constraint qa_reject_hour"); }
    }

    @Test void simultaneousSavesProduceOneCompleteWeek() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.haftayiKaydet(owner, week(9, 7)); });
            var second = pool.submit(() -> { start.await(); return service.haftayiKaydet(owner, week(10, 7)); });
            start.countDown(); first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        var saved = service.listele(owner, personId);
        assertEquals(7, saved.size());
        assertEquals(1, saved.stream().map(CalismaSaatiDtos.CalismaSaatiResponse::startTime).distinct().count());
    }

    @Test void legacyPostCannotDuplicateAWeekday() {
        service.haftayiKaydet(owner, week(9, 5));
        assertThrows(ApiException.class, () -> service.olustur(owner, new CalismaSaatiDtos.CalismaSaatiRequest(
                personId, 0, LocalTime.of(10, 0), LocalTime.of(18, 0))));
        assertEquals(5, service.listele(owner, personId).size());
    }

    @Test void foreignTenantCannotReadOrWriteStaffSchedule() {
        service.haftayiKaydet(owner, week(9, 5));
        var foreign = new AuthenticatedUser(owner.userId(), UUID.randomUUID(), "OWNER");
        assertThrows(ApiException.class, () -> service.haftayiKaydet(foreign, week(10, 5)));
        assertThrows(ApiException.class, () -> service.listele(foreign, personId));
        assertEquals(LocalTime.of(9, 0), service.listele(owner, personId).getFirst().startTime());
    }

    @Test void httpPutEnforcesRoleAndValidatesAllSevenDays() throws Exception {
        String days = IntStream.range(0, 7).mapToObj(day -> "{\"dayOfWeek\":" + day + ",\"closed\":true,\"startTime\":null,\"endTime\":null}")
                .collect(java.util.stream.Collectors.joining(","));
        String body = "{\"personelId\":\"" + personId + "\",\"days\":[" + days + "]}";
        assertEquals(403, put(body, "STAFF"));
        assertEquals(200, put(body, "OWNER"));
        assertEquals(200, put(body, "MANAGER"));
        assertEquals(400, put("{\"personelId\":\"" + personId + "\",\"days\":[]}", "OWNER"));
    }

    private int put(String body, String role) throws Exception {
        var token = jwt.generateAccessToken(owner.userId(), owner.tenantId(), role);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/working-hours"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
