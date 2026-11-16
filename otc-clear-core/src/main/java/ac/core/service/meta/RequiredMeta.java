package ac.core.service.meta;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Meta values the application itself relies on.
 *
 * <p>The value types and roles below map to the internal {@code FieldType} / {@code FieldRole}
 * enumerations, so they must always exist and must never be renamed, disabled or deleted. Everything
 * else in the dictionaries is data the user may maintain freely.
 */
public final class RequiredMeta {

    /** One required value of a built-in set. */
    public record ItemDef(String code, String label, String refSetCode) {
    }

    /** One built-in set with its required values. */
    public record SetDef(String code, String name, String description, List<ItemDef> items) {
    }

    public static final String VALUE_TYPE_SET = "VALUE_TYPE";
    public static final String FIELD_ROLE_SET = "FIELD_ROLE";

    public static final List<SetDef> SETS = List.of(
            new SetDef(VALUE_TYPE_SET, "值类型", "字段取值的类型", List.of(
                    new ItemDef("TEXT", "文本", null),
                    new ItemDef("INTEGER", "整数", null),
                    new ItemDef("DECIMAL", "小数", null),
                    new ItemDef("DATE", "日期", null),
                    new ItemDef("TIME", "时间", null),
                    new ItemDef("DATETIME", "日期时间", null),
                    new ItemDef("BOOLEAN", "布尔", null))),
            new SetDef(FIELD_ROLE_SET, "角色类型", "字段在交易块中的角色", List.of(
                    new ItemDef("BUSINESS", "业务字段", null),
                    new ItemDef("MANAGED", "管理字段", null))));

    private RequiredMeta() {
    }

    /** Definition of one required set. */
    public static Optional<SetDef> set(String setCode) {
        String normalized = normalize(setCode);
        return SETS.stream().filter(def -> def.code().equals(normalized)).findFirst();
    }

    /** True when the value is required by the application and therefore read only. */
    public static boolean isProtected(String setCode, String itemCode) {
        String normalizedItem = normalize(itemCode);
        return set(setCode).map(def -> def.items().stream()
                .anyMatch(item -> item.code().equals(normalizedItem))).orElse(false);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
