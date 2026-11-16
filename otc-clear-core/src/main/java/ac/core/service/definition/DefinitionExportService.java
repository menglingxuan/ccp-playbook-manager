package ac.core.service.definition;

import ac.core.common.TimeProvider;
import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.MetaRepository;
import ac.core.persistence.ProductRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Builds the backup files of the definition catalogue.
 *
 * <p>Two scopes are offered, product definitions and the meta dictionary. Each can be written as an
 * Excel workbook (the editable, restorable form) or as a replayable SQL script (the migration and
 * backup form, see {@link DefinitionSqlWriter}).
 */
@Service
public class DefinitionExportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MetaRepository meta;
    private final BaseProductRepository bases;
    private final ProductRepository products;
    private final TimeProvider time;

    public DefinitionExportService(MetaRepository meta, BaseProductRepository bases,
            ProductRepository products, TimeProvider time) {
        this.meta = meta;
        this.bases = bases;
        this.products = products;
        this.time = time;
    }

    /**
     * One file ready to download.
     *
     * @param fileName    suggested name
     * @param contentType media type
     * @param content     file bytes
     */
    public record File(String fileName, String contentType, byte[] content) {
    }

    /** Collects the rows of one scope. */
    public DefinitionBundle bundle(String tenantId, DefinitionScope scope) {
        Map<DefinitionTable, List<DefinitionBundle.Row>> tables = new LinkedHashMap<>();
        for (DefinitionTable table : scope.tables()) {
            tables.put(table, new ArrayList<>());
        }
        if (scope == DefinitionScope.PRODUCT) {
            for (MetaRepository.PlatformRow platform : meta.platforms()) {
                tables.get(DefinitionTable.PLATFORM).add(DefinitionBundle.Row.of(Map.of())
                        .put("code", platform.code())
                        .put("name", platform.name())
                        .put("description", platform.description())
                        .put("sort_order", Integer.toString(platform.sortOrder()))
                        .put("enabled", Boolean.toString(platform.enabled())));
            }
            for (BaseProductRepository.BaseProductRow base : bases.listAll(tenantId)) {
                tables.get(DefinitionTable.BASE_PRODUCT).add(DefinitionBundle.Row.of(Map.of())
                        .put("platform_code", base.platformCode())
                        .put("code", base.code())
                        .put("name", base.name())
                        .put("description", base.description())
                        .put("status", base.status())
                        .put("sort_order", Integer.toString(base.sortOrder()))
                        .put("schema_hash", base.schemaHash()));
                for (BaseProductRepository.BaseFieldRow field : bases.listFields(tenantId, base.id())) {
                    tables.get(DefinitionTable.BASE_FIELD).add(fieldRow(base.platformCode(), base.code(),
                            field.fieldKey(), field.header(), field.displayName(), field.aliases(),
                            field.valueTypeCode(), field.roleCode(), field.tags(), field.required(),
                            field.businessKey(), field.keyFlag(), field.sortOrder(), field.remark(),
                            field.formatHint(), field.clonePattern(), field.validationExpr()));
                }
            }
            for (ProductRepository.ProductRow product : products.listAll(tenantId)) {
                tables.get(DefinitionTable.PRODUCT).add(DefinitionBundle.Row.of(Map.of())
                        .put("platform_code", product.platformCode())
                        .put("base_code", product.baseProductCode())
                        .put("code", product.code())
                        .put("name", product.name())
                        .put("description", product.description())
                        .put("status", product.status())
                        .put("strict_match", Boolean.toString(product.strictMatch()))
                        .put("schema_hash", product.schemaHash()));
                for (ProductRepository.ProductFieldRow field : products.listFields(tenantId, product.id())) {
                    tables.get(DefinitionTable.PRODUCT_FIELD).add(DefinitionBundle.Row.of(Map.of())
                            .put("product_code", product.code())
                            .put("field_key", field.fieldKey())
                            .put("header", field.header())
                            .put("display_name", field.displayName())
                            .put("aliases", join(field.aliases()))
                            .put("value_type_code", field.valueTypeCode())
                            .put("role_code", field.roleCode())
                            .put("tags", join(field.tags()))
                            .put("required", Boolean.toString(field.required()))
                            .put("business_key", Boolean.toString(field.businessKey()))
                            .put("key_flag", Boolean.toString(field.keyFlag()))
                            .put("sort_order", Integer.toString(field.sortOrder()))
                            .put("remark", field.remark())
                            .put("format_hint", field.formatHint())
                            .put("clone_pattern", field.clonePattern())
                            .put("validation_expr", field.validationExpr()));
                }
            }
        } else {
            for (MetaRepository.MetaSetRow set : meta.sets(tenantId)) {
                tables.get(DefinitionTable.META_SET).add(DefinitionBundle.Row.of(Map.of())
                        .put("code", set.code())
                        .put("name", set.name())
                        .put("description", set.description())
                        .put("built_in", Boolean.toString(set.builtIn()))
                        .put("sort_order", Integer.toString(set.sortOrder()))
                        .put("type", set.setType()));
            }
            for (MetaRepository.MetaItemRow item : meta.allItems(tenantId)) {
                tables.get(DefinitionTable.META_ITEM).add(DefinitionBundle.Row.of(Map.of())
                        .put("set_code", item.setCode())
                        .put("code", item.code())
                        .put("label", item.label())
                        .put("ref_set_code", item.refSetCode())
                        .put("sort_order", Integer.toString(item.sortOrder()))
                        .put("enabled", Boolean.toString(item.enabled()))
                        .put("remark", item.remark())
                        .put("content", item.content())
                        .put("rule_type", item.ruleType())
                        .put("severity", item.severity()));
            }
        }
        return new DefinitionBundle(tenantId, tables);
    }

    /** Excel workbook of one scope; the format the import accepts. */
    public File excel(String tenantId, DefinitionScope scope) {
        byte[] content = DefinitionExcel.write(bundle(tenantId, scope));
        return new File(fileName(tenantId, scope, "xlsx"),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    /** Replayable SQL script of one scope. */
    public File sql(String tenantId, DefinitionScope scope) {
        String script = DefinitionSqlWriter.write(bundle(tenantId, scope), scope, time.nowUtc());
        return new File(fileName(tenantId, scope, "sql"), "application/sql;charset=UTF-8",
                script.getBytes(StandardCharsets.UTF_8));
    }

    /** File of the requested format. */
    public File export(String tenantId, DefinitionScope scope, String format) {
        String target = format == null || format.isBlank() ? "xlsx" : format.trim().toLowerCase(Locale.ROOT);
        return switch (target) {
            case "xlsx" -> excel(tenantId, scope);
            case "sql" -> sql(tenantId, scope);
            default -> throw ac.core.common.ApiException.invalid("Format must be xlsx or sql");
        };
    }

    /** {@code product-definitions-default-20260101-120000.xlsx}. */
    private String fileName(String tenantId, DefinitionScope scope, String extension) {
        String prefix = scope == DefinitionScope.PRODUCT ? "product-definitions" : "meta-dictionary";
        LocalDateTime now = time.nowUtc();
        return prefix + "-" + tenantId + "-" + now.format(STAMP) + "." + extension;
    }

    private static DefinitionBundle.Row fieldRow(String platformCode, String baseCode, String fieldKey,
            String header, String displayName, List<String> aliases, String valueTypeCode, String roleCode,
            List<String> tags, boolean required, boolean businessKey, boolean keyFlag, int sortOrder,
            String remark, String formatHint, String clonePattern, String validationExpr) {
        return DefinitionBundle.Row.of(Map.of())
                .put("platform_code", platformCode)
                .put("base_code", baseCode)
                .put("field_key", fieldKey)
                .put("header", header)
                .put("display_name", displayName)
                .put("aliases", join(aliases))
                .put("value_type_code", valueTypeCode)
                .put("role_code", roleCode)
                .put("tags", join(tags))
                .put("required", Boolean.toString(required))
                .put("business_key", Boolean.toString(businessKey))
                .put("key_flag", Boolean.toString(keyFlag))
                .put("sort_order", Integer.toString(sortOrder))
                .put("remark", remark)
                .put("format_hint", formatHint)
                .put("clone_pattern", clonePattern)
                .put("validation_expr", validationExpr);
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }
}
