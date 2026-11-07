package ac.domain.product;

import java.util.Locale;
import java.util.Optional;

/** Fixed set of trading platforms. The catalogue is small and part of the domain. */
public enum PlatformCode {

    MW("MarkitWire"),
    CFETS("CFETS");

    private final String displayName;

    PlatformCode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<PlatformCode> find(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (PlatformCode code : values()) {
            if (code.name().equals(normalized)) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }
}
