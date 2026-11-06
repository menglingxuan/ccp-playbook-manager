package ac.domain.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic SHA-256 helpers shared by record ids, schema hashes and file fingerprints. */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Returns the first {@code length} characters of the hash, or the whole hash when shorter. */
    public static String prefix(String hash, int length) {
        if (hash == null) {
            return "";
        }
        return hash.length() <= length ? hash : hash.substring(0, length);
    }
}
