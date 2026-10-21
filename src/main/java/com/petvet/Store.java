package com.petvet;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Everything in memory, behind the store's lock. */
public class Store {

    public static class User {
        public long id;
        public String email;
        public String name;
        public String password;
        public String role;
    }

    public static class Pet implements Serializable {
        private static final long serialVersionUID = 1L;
        public long id;
        public long ownerId;
        public String name;
        public String species;
        public String notes = "";
    }

    public static class Visit {
        public long id;
        public long petId;
        public long vetId;
        public String notes;
    }

    public final Map<Long, User> users = new LinkedHashMap<>();
    public final Map<Long, Pet> pets = new LinkedHashMap<>();
    public final Map<Long, Visit> visits = new LinkedHashMap<>();
    public final Map<String, Long> sessions = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    public Store() {
        // Demo data: one vet and two owners.
        addUser(1, "vera@vet.test", "Vera", "vera-pass-1", "vet");
        addUser(2, "omar@mail.test", "Omar", "omar-pass-2", "owner");
        addUser(3, "pia@mail.test", "Pia", "pia-pass-3", "owner");
        addPet(1, 2, "Rex", "dog", "Allergic to chicken");
        addPet(2, 3, "Luna", "cat", "On insulin, 2 units twice a day");
    }

    public long nextId() {
        return ids.incrementAndGet();
    }

    private void addUser(long id, String email, String name, String password, String role) {
        User u = new User();
        u.id = id;
        u.email = email;
        u.name = name;
        u.password = Passwords.hash(password);
        u.role = role;
        users.put(id, u);
    }

    private void addPet(long id, long ownerId, String name, String species, String notes) {
        Pet p = new Pet();
        p.id = id;
        p.ownerId = ownerId;
        p.name = name;
        p.species = species;
        p.notes = notes;
        pets.put(id, p);
    }

    public User userByEmail(String email) {
        return users.values().stream().filter(u -> u.email.equals(email)).findFirst().orElse(null);
    }
}
