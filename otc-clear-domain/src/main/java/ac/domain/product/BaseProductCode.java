package ac.domain.product;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fixed set of base products. A base product owns the complete field definition; concrete products
 * derive from it and may only drop fields.
 */
public enum BaseProductCode {

    IRS("Interest Rate Swap"),
    OIS("Overnight Index Swap"),
    BSW("Basis Swap"),
    BWOIS("Basis Overnight Index Swap"),
    CCSIRS("Cross Currency Swap"),
    CCSBSW("Cross Currency Basis Swap"),
    NDS("Non-Deliverable Swap"),
    CNYNDS("CNY Non-Deliverable Swap");

    private final String displayName;

    BaseProductCode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static List<BaseProductCode> all() {
        return List.of(values());
    }

    public static Optional<BaseProductCode> find(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (BaseProductCode code : values()) {
            if (code.name().equals(normalized)) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }
}
