package com.saas.backend.personel;

import com.saas.backend.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regresyon: davetle gelen personel (pasif eklenip kabulde aktifleşir) 3D sahnede koltuk alır.
 * Eskiden tetikleyici yalnızca INSERT'te çalıştığı için bu personelin koltuğu hiç oluşmuyordu.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffChairsIntegrationTests extends PostgresTestBase {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    @Value("${local.server.port}") int port;

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response, int... accepted) throws Exception {
        boolean match = false;
        for (int status : accepted) match |= response.statusCode() == status;
        assertTrue(match, response.statusCode() + " " + response.body());
        return response.body().isBlank() ? null : json.readTree(response.body());
    }

    private int chairs(String slug) throws Exception {
        return ok(send("GET", "/api/public/v1/shops/" + slug + "/scene", null, null), 200).get("chairs").size();
    }

    @Test
    void invitedStaffGetsAChairOnAcceptanceAndOwnerCanAddSelf() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@test.example";
        var owner = ok(send("POST", "/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"Gizli1234\",\"fullName\":\"Ada Usta\",\"tenantName\":\"Koltuk Berber\"}", null), 201);
        String token = owner.get("accessToken").asString();
        String slug = ok(send("GET", "/api/booking-link", null, token), 200).get("tenantSlug").asString();
        assertEquals(0, chairs(slug), "yeni işletmede çalışan yokken koltuk olmaz");

        // Sahip kendini berber yapar (tek başına çalışan berber); tekrar çağrı aynı kaydı döndürür.
        var self = ok(send("POST", "/api/staff/self", null, token), 200);
        assertTrue(self.get("self").asBoolean());
        assertEquals("Ada Usta", self.get("displayName").asString());
        assertEquals(self.get("id").asString(), ok(send("POST", "/api/staff/self", null, token), 200).get("id").asString());
        assertEquals(1, chairs(slug));
        assertEquals(1, ok(send("GET", "/api/staff", null, token), 200).size());

        // Davet: e-posta canlı değilken bağlantı sahibe döner; kabul edilene kadar bekleyen listede durur.
        String invitee = "yeni-" + UUID.randomUUID() + "@test.example";
        var invite = ok(send("POST", "/api/staff/invite", "{\"email\":\"" + invitee + "\",\"displayName\":\"Davetli Berber\"}", token), 201);
        String link = invite.get("inviteLink").asString();
        assertTrue(link.contains("/personel-daveti?token="), link);
        assertEquals(1, ok(send("GET", "/api/staff/invitations", null, token), 200).size());
        assertEquals(1, chairs(slug), "davet kabul edilmeden koltuk eklenmez");

        // Kabul: personel aktifleşir (UPDATE) ve koltuk bağlanır. Eskiden 1'de kalıyordu.
        String inviteToken = URLDecoder.decode(link.substring(link.indexOf("token=") + 6), StandardCharsets.UTF_8);
        ok(send("POST", "/api/staff/invitations/accept", "{\"token\":\"" + inviteToken + "\",\"password\":\"Gizli1234\"}", null), 204);
        assertEquals(2, chairs(slug), "davetle gelen personel koltuk almalı");
        assertEquals(0, ok(send("GET", "/api/staff/invitations", null, token), 200).size());

        // FREE plan en fazla 2 aktif personel: üçüncüsü kota hatası verir.
        var third = send("POST", "/api/staff/invite", "{\"email\":\"" + "x-" + UUID.randomUUID() + "@test.example" + "\",\"displayName\":\"Üçüncü\"}", token);
        assertEquals(403, third.statusCode(), third.body());

        // Koltuksuz taslak kaydedilebilir (sahip koltukları düzenleyebilsin); koltuksuz yayın reddedilir.
        ObjectNode state = (ObjectNode) ok(send("GET", "/api/shop-design", null, token), 200);
        ObjectNode draft = (ObjectNode) state.get("draft");
        draft.putArray("chairs");
        var saved = ok(send("PUT", "/api/shop-design/draft", json.writeValueAsString(draft), token), 200);
        var publish = send("POST", "/api/shop-design/publish", "{\"revision\":" + saved.get("draft").get("revision").asInt() + "}", token);
        assertEquals(400, publish.statusCode(), publish.body());
        assertEquals("EMPTY_SCENE", json.readTree(publish.body()).get("code").asString());
    }
}
