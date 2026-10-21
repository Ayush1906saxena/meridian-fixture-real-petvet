package com.petvet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Petvet's API: the JDK's HTTP server, JSON through Jackson, data in a Store. */
public class App {

    private static final ObjectMapper JSON = new ObjectMapper();

    public final Store store = new Store();
    private HttpServer server;

    public static void main(String[] args) throws IOException {
        App app = new App();
        app.start(Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")));
        System.out.println("Petvet on http://localhost:" + app.port());
    }

    public App start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    record Reply(int status, Object body) {
    }

    private void handle(HttpExchange ex) throws IOException {
        Reply reply;
        try {
            reply = route(ex);
        } catch (Exception e) {
            // Show what went wrong, so problems are easy to report.
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            reply = new Reply(500, Map.of("error", e.toString(), "trace", trace.toString()));
        }
        byte[] out = JSON.writeValueAsBytes(reply.body());
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private Reply route(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        byte[] body = ex.getRequestBody().readAllBytes();
        if (method.equals("POST") && path.equals("/api/register")) {
            return register(body);
        }
        if (method.equals("POST") && path.equals("/api/login")) {
            return login(body);
        }
        Store.User user = user(ex);
        if (user == null) {
            return new Reply(401, Map.of("error", "please log in"));
        }
        if (method.equals("POST") && path.equals("/api/logout")) {
            return new Reply(200, Map.of("ok", true));
        }
        if (method.equals("GET") && path.equals("/api/pets")) {
            return listPets(user);
        }
        if (path.startsWith("/api/pets/")) {
            long id = Long.parseLong(path.substring("/api/pets/".length()));
            if (method.equals("GET")) {
                return getPet(user, id);
            }
            if (method.equals("PUT")) {
                return updatePet(user, id, body);
            }
        }
        if (method.equals("POST") && path.equals("/api/visits")) {
            return addVisit(user, body);
        }
        if (method.equals("POST") && path.equals("/api/import")) {
            return importPets(user, ex.getRequestHeaders().getFirst("Content-Type"), body);
        }
        if (method.equals("POST") && path.equals("/api/import/xml")) {
            return importXml(user, body);
        }
        return new Reply(404, Map.of("error", "not found"));
    }

    private Store.User user(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        synchronized (store) {
            Long id = store.sessions.get(header.substring(7));
            return id == null ? null : store.users.get(id);
        }
    }

    private static Map<String, Object> publicUser(Store.User u) {
        return Map.of("id", u.id, "email", u.email, "name", u.name, "role", u.role);
    }

    // ---- accounts

    private Reply register(byte[] body) throws IOException {
        Map<String, String> in = JSON.readValue(body, new TypeReference<>() { });
        String email = in.get("email");
        String name = in.get("name");
        String password = in.get("password");
        if (email == null || name == null || password == null) {
            return new Reply(400, Map.of("error", "email, name and password are required"));
        }
        synchronized (store) {
            if (store.userByEmail(email) != null) {
                return new Reply(409, Map.of("error", "that email already has an account"));
            }
            Store.User u = new Store.User();
            u.id = store.nextId();
            u.email = email;
            u.name = name;
            u.password = Passwords.hash(password);
            u.role = "owner";
            store.users.put(u.id, u);
            return new Reply(201, Map.of("user", publicUser(u)));
        }
    }

    private Reply login(byte[] body) throws IOException {
        Map<String, String> in = JSON.readValue(body, new TypeReference<>() { });
        synchronized (store) {
            Store.User u = store.userByEmail(String.valueOf(in.get("email")));
            if (u == null || !Passwords.matches(String.valueOf(in.get("password")), u.password)) {
                return new Reply(401, Map.of("error", "wrong email or password"));
            }
            String token = UUID.randomUUID().toString();
            store.sessions.put(token, u.id);
            return new Reply(200, Map.of("token", token, "user", publicUser(u)));
        }
    }

    // ---- pets and visits

    private Reply listPets(Store.User user) {
        synchronized (store) {
            List<Store.Pet> out = new ArrayList<>();
            for (Store.Pet p : store.pets.values()) {
                if (user.role.equals("vet") || p.ownerId == user.id) {
                    out.add(p);
                }
            }
            return new Reply(200, Map.of("pets", out));
        }
    }

    private Reply getPet(Store.User user, long id) {
        synchronized (store) {
            Store.Pet p = store.pets.get(id);
            return p == null ? new Reply(404, Map.of("error", "no such pet")) : new Reply(200, Map.of("pet", p));
        }
    }

    private Reply updatePet(Store.User user, long id, byte[] body) throws IOException {
        synchronized (store) {
            Store.Pet p = store.pets.get(id);
            if (p == null || (p.ownerId != user.id && !user.role.equals("vet"))) {
                return new Reply(404, Map.of("error", "no such pet"));
            }
            JSON.readerForUpdating(p).readValue(body);
            return new Reply(200, Map.of("pet", p));
        }
    }

    private Reply addVisit(Store.User user, byte[] body) throws IOException {
        Map<String, Object> in = JSON.readValue(body, new TypeReference<>() { });
        synchronized (store) {
            Store.Pet p = store.pets.get(((Number) in.get("petId")).longValue());
            if (p == null) {
                return new Reply(400, Map.of("error", "no such pet"));
            }
            Store.Visit v = new Store.Visit();
            v.id = store.nextId();
            v.petId = p.id;
            v.vetId = user.id;
            v.notes = String.valueOf(in.getOrDefault("notes", ""));
            store.visits.put(v.id, v);
            return new Reply(201, Map.of("visit", v));
        }
    }

    // ---- imports from the old system

    @SuppressWarnings("unchecked")
    private Reply importPets(Store.User user, String contentType, byte[] body) throws Exception {
        List<Store.Pet> incoming;
        if ("application/x-java-serialized-object".equals(contentType)) {
            // The old desktop app exported its pets as a serialized list.
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(body))) {
                incoming = (List<Store.Pet>) in.readObject();
            }
        } else {
            incoming = JSON.readValue(body, new TypeReference<List<Store.Pet>>() { });
        }
        return addImported(user, incoming);
    }

    private Reply importXml(Store.User user, byte[] body) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(body));
        NodeList nodes = doc.getElementsByTagName("pet");
        List<Store.Pet> incoming = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            Store.Pet p = new Store.Pet();
            p.name = e.getElementsByTagName("name").item(0).getTextContent();
            p.species = e.getElementsByTagName("species").item(0).getTextContent();
            incoming.add(p);
        }
        return addImported(user, incoming);
    }

    private Reply addImported(Store.User user, List<Store.Pet> incoming) {
        synchronized (store) {
            List<Map<String, Object>> added = new ArrayList<>();
            for (Store.Pet in : incoming) {
                Store.Pet p = new Store.Pet();
                p.id = store.nextId();
                p.ownerId = user.id;
                p.name = in.name;
                p.species = in.species;
                store.pets.put(p.id, p);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", p.id);
                row.put("name", p.name);
                row.put("species", p.species);
                added.add(row);
            }
            return new Reply(201, Map.of("imported", added));
        }
    }
}
