package com.petvet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The happy paths: register, log in, see and rename your pets, import from the old system, a vet records a visit. */
class AppTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    App app;

    @BeforeEach
    void start() throws Exception {
        app = new App().start(0);
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    record Res(int status, JsonNode json, String text) {
    }

    Res call(String method, String path, String body, String token, String type) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .header("Content-Type", type == null ? "application/json" : type)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json;
        try {
            json = JSON.readTree(r.body());
        } catch (Exception e) {
            json = null;
        }
        return new Res(r.statusCode(), json, r.body());
    }

    Res call(String method, String path, String body, String token) throws Exception {
        return call(method, path, body, token, null);
    }

    String login(String email, String password) throws Exception {
        Res r = call("POST", "/api/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null);
        assertEquals(200, r.status(), email + " should log in: " + r.text());
        return r.json().get("token").asText();
    }

    @Test
    void anOwnerRegistersLogsInAndRenamesTheirPet() throws Exception {
        Res reg = call("POST", "/api/register", "{\"email\":\"ada@mail.test\",\"name\":\"Ada\",\"password\":\"ada-pass-9\"}", null);
        assertEquals(201, reg.status());
        assertEquals("owner", reg.json().get("user").get("role").asText());
        login("ada@mail.test", "ada-pass-9");
        String omar = login("omar@mail.test", "omar-pass-2");
        Res pets = call("GET", "/api/pets", null, omar);
        assertEquals(1, pets.json().get("pets").size());
        Res renamed = call("PUT", "/api/pets/1", "{\"name\":\"Rex II\"}", omar);
        assertEquals(200, renamed.status());
        assertEquals("Rex II", call("GET", "/api/pets/1", null, omar).json().get("pet").get("name").asText());
        assertEquals(401, call("POST", "/api/login", "{\"email\":\"omar@mail.test\",\"password\":\"nope\"}", null).status());
    }

    @Test
    void theOldSystemsPetsImportFromJsonAndXml() throws Exception {
        String pia = login("pia@mail.test", "pia-pass-3");
        Res json = call("POST", "/api/import", "[{\"name\":\"Milo\",\"species\":\"dog\"}]", pia);
        assertEquals(201, json.status(), json.text());
        Res xml = call("POST", "/api/import/xml",
                "<pets><pet><name>Nala</name><species>cat</species></pet></pets>", pia, "application/xml");
        assertEquals(201, xml.status(), xml.text());
        assertEquals("Nala", xml.json().get("imported").get(0).get("name").asText());
        assertEquals(3, call("GET", "/api/pets", null, pia).json().get("pets").size());
    }

    @Test
    void aVetSeesEveryPetAndRecordsAVisit() throws Exception {
        String vera = login("vera@vet.test", "vera-pass-1");
        assertTrue(call("GET", "/api/pets", null, vera).json().get("pets").size() >= 2);
        Res visit = call("POST", "/api/visits", "{\"petId\":2,\"notes\":\"Weight stable\"}", vera);
        assertEquals(201, visit.status(), visit.text());
        assertEquals(200, call("POST", "/api/logout", null, vera).status());
    }
}
