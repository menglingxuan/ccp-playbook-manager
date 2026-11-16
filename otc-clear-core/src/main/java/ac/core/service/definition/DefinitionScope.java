package ac.core.service.definition;

import java.util.List;
import java.util.Locale;

/**
 * Part of the definition catalogue that can be exported and restored.
 *
 * <p>The two scopes are independent: product definitions describe the catalogue, the meta scope
 * holds the dictionaries the definitions refer to.
 */
public enum DefinitionScope {

    /** Platforms, base products with their fields, and the derived products. */
    PRODUCT("产品定义"),

    /** Meta sets and their values: value types, roles, tags, formulas and validation rules. */
    META("元数据");

    private final String label;

    DefinitionScope(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Tables written by this scope, in dependency order. */
    public List<DefinitionTable> tables() {
        return DefinitionTable.byScope(this);
    }

    /** Parses the scope of a request path or query value. */
    public static DefinitionScope of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Definition scope is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown definition scope '" + value + "'");
        }
    }
}
