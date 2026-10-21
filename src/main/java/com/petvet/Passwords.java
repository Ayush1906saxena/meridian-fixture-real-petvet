package com.petvet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Password hashing. */
public final class Passwords {

    private Passwords() {
    }

    public static String hash(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String password, String stored) {
        return hash(password).equals(stored);
    }
}
