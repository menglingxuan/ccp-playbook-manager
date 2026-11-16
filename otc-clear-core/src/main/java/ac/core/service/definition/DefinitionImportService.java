package ac.core.service.definition;

import ac.core.common.ApiException;
import ac.core.common.Ids;
import ac.core.common.TimeProvider;
import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.MetaRepository;
import ac.core.persistence.ProductRepository;
import ac.core.service.audit.AuditModule;
import ac.core.service.audit.AuditService;
import ac.core.service.product.MetaLookup;
import ac.core.service.meta.MetaSetType;
import ac.core.service.meta.RequiredMeta;
import ac.core.service.product.ProductSchemaMapper;
import ac.domain.validation.RuleSyntaxException;
import ac.domain.validation.ValidationRule;
import ac.domain.validation.ValidationRuleType;
import ac.domain.validation.ValidationSeverity;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Previews and applies a definition workbook, which is the restore path of an export.
 *
 * <p>A preview never writes. It classifies every row of the file as an addition, a conflict with the
 * stored definition, an unchanged row or an invalid row, and keeps the parsed bundle until the
 * operator decides. Applying the plan then follows the selected mode:
 * <ul>
 *   <li>{@code MERGE_OVERWRITE} writes additions and overwrites conflicts;</li>
 *   <li>{@code MERGE_SKIP} writes additions only and keeps conflicting definitions as they are;</li>
 *   <li>{@code FULL_RESTORE} clears the scope first (keeping the values the application requires)
 *       and then writes everything the file defines.</li>
 * </ul>
 * Invalid rows are never written and carry the reason, so a partially broken file still restores
 * everything that is sound.
 */
@Service
public class DefinitionImportService {

    /** How long a previewed plan stays usable. */
    static final Duration PLAN_TTL = Duration.ofMinutes(30);

    /** Upper bound of stored plans; keeps a long session from growing without end. */
    static final int MAX_PLANS = 20;

    /** How a row of the file relates to the stored definition. */
    public enum Status {

        /** Not stored yet, will be created. */
        ADD,

        /** Stored with different content; overwritten or skipped depending on the mode. */
        CONFLICT,

        /** Stored with identical content, nothing to do. */
        UNCHANGED,

        /** Cannot be written; the messages explain why. */
        INVALID
    }

    /** Handling selected by the operator. */
    public enum ImportMode {

        /** Add new rows and overwrite conflicting ones. */
        MERGE_OVERWRITE,

        /** Add new rows only, keep every stored definition as it is. */
        MERGE_SKIP,

        /** Clear the scope first, then write the file. */
        FULL_RESTORE;

        /** Parses the requested mode, defaulting to {@link #MERGE_OVERWRITE}. */
        public static ImportMode of(String value) {
            if (value == null || value.isBlank()) {
                return MERGE_OVERWRITE;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("Unknown import mode '" + value + "'");
            }
        }
    }

    /**
     * One classified row.
     *
     * @param table    table the row belongs to (worksheet name)
     * @param key      business key, e.g. {@code MW/IRS/tradeId}
     * @param status   classification
     * @param messages reasons for a conflict or an invalid row
     */
    public record PlanItem(String table, String key, Status status, List<String> messages) {
        public PlanItem {
            messages = List.copyOf(messages);
        }
    }

    /**
     * A previewed import, kept until the operator applies or abandons it.
     *
     * @param planId    identifier of the plan
     * @param scope     product definitions or meta dictionary
     * @param fileName  name of the uploaded workbook
     * @param createdAt creation timestamp, plans expire after {@link #PLAN_TTL}
     * @param counts    rows per status
     * @param rows      classified rows in write order
     * @param notes     remarks about the file itself
     */
    public record Plan(String planId, DefinitionScope scope, String fileName, Instant createdAt,
            Map<String, Integer> counts, List<PlanItem> rows, List<String> notes) {

        public Plan {
            counts = Map.copyOf(counts);
            rows = List.copyOf(rows);
            notes = List.copyOf(notes);
        }
    }

    /**
     * Outcome of an applied plan.
     *
     * @param created rows inserted
     * @param updated stored rows overwritten
     * @param skipped conflicting rows left untouched
     * @param invalid rows that could not be written
     * @param deleted rows removed by a full restore
     * @param notes   remarks worth showing, e.g. protected values kept
     */
    public record Result(String scope, String mode, int created, int updated, int skipped, int invalid,
            int deleted, List<String> notes) {

        public Result {
            notes = List.copyOf(notes);
        }
    }

    private final MetaRepository meta;
    private final BaseProductRepository bases;
    private final ProductRepository products;
    private final ProductSchemaMapper schemaMapper;
    private final MetaLookup metaLookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final Map<String, Stored> plans = new ConcurrentHashMap<>();

    public DefinitionImportService(MetaRepository meta, BaseProductRepository bases,
            ProductRepository products, ProductSchemaMapper schemaMapper, MetaLookup metaLookup,
            AuditService audit, TimeProvider time) {
        this.meta = meta;
        this.bases = bases;
        this.products = products;
        this.schemaMapper = schemaMapper;
        this.metaLookup = metaLookup;
        this.audit = audit;
        this.time = time;
    }

    /** A plan together with the parsed workbook it came from. */
    private record Stored(Plan plan, DefinitionBundle bundle) {
    }

    /**
     * Parses and classifies a workbook without writing anything.
     *
     * @param tenantId tenant the definitions belong to
     * @param scope    product definitions or meta dictionary
     * @param fileName name shown in the panel
     * @param content  workbook content
     */
    public Plan preview(String tenantId, DefinitionScope scope, String fileName, InputStream content) {
        DefinitionBundle bundle = DefinitionExcel.read(scope, content, tenantId);
        Evaluation evaluation = evaluate(tenantId, bundle);
        String planId = "plan-" + Ids.newId();
        Plan plan = new Plan(planId, scope, fileName, Instant.now(), counts(evaluation.items()),
                evaluation.items(), evaluation.notes());
        store(new Stored(plan, bundle));
        return plan;
    }

    /** Returns a stored plan; unknown or expired plans answer 404. */
    public Plan require(String planId) {
        return requireStored(planId).plan();
    }

    /** Forgets a previewed plan. */
    public void discard(String planId) {
        plans.remove(planId);
    }

    /**
     * Applies a previewed plan.
     *
     * @param tenantId tenant the definitions belong to
     * @param planId   identifier returned by {@link #preview}
     * @param mode     handling of conflicting rows
     */
    @Transactional
    public Result apply(String tenantId, String planId, ImportMode mode) {
        Stored stored = requireStored(planId);
        // re-classify against the current database, it may have changed since the preview
        Evaluation evaluation = evaluate(tenantId, stored.bundle());
        List<String> notes = new ArrayList<>(evaluation.notes());
        int deleted = mode == ImportMode.FULL_RESTORE ? clear(tenantId, stored.plan().scope(), notes) : 0;

        Writer writer = new Writer(tenantId);
        int skipped = 0;
        boolean cleared = mode == ImportMode.FULL_RESTORE;
        for (Classified classified : evaluation.rows()) {
            if (classified.item().status() == Status.INVALID) {
                continue;
            }
            if (cleared) {
                // everything was deleted, so even an unchanged row has to be written again
                writer.create(classified);
                continue;
            }
            if (classified.item().status() == Status.UNCHANGED) {
                continue;
            }
            if (classified.item().status() == Status.CONFLICT && mode == ImportMode.MERGE_SKIP) {
                skipped++;
                notes.add(classified.item().key() + "：保留现有定义");
                continue;
            }
            if (classified.existing() == null) {
                writer.create(classified);
            } else {
                writer.update(classified);
            }
        }
        writer.refreshSchemaHashes();

        Result result = new Result(stored.plan().scope().name(), mode.name(), writer.created, writer.updated,
                skipped, evaluation.invalidCount(), deleted, notes);
        audit.record(tenantId,
                stored.plan().scope() == DefinitionScope.META ? AuditModule.META_DICTIONARY
                        : AuditModule.PRODUCT_DEFINITION,
                stored.plan().scope().name(), stored.plan().fileName(), "IMPORT",
                Map.of("created", result.created(), "updated", result.updated(), "skipped", result.skipped(),
                        "invalid", result.invalid(), "deleted", result.deleted(), "mode", mode.name()));
        discard(planId);
        return result;
    }

    private Stored requireStored(String planId) {
        Stored stored = plans.get(planId);
        if (stored == null) {
            throw ApiException.notFound("The import preview " + planId + " is unknown, upload the file again");
        }
        if (stored.plan().createdAt().isBefore(Instant.now().minus(PLAN_TTL))) {
            plans.remove(planId);
            throw ApiException.notFound("The import preview " + planId + " expired, upload the file again");
        }
        return stored;
    }

    private void store(Stored stored) {
        String tenantId = stored.bundle().tenantId();
        Instant cutoff = Instant.now().minus(PLAN_TTL);
        plans.entrySet().removeIf(entry -> entry.getValue().plan().createdAt().isBefore(cutoff));
        // the cap is per tenant: a busy operator can never push another operator's preview out
        long tenantPlans = plans.values().stream()
                .filter(value -> tenantId.equals(value.bundle().tenantId())).count();
        while (tenantPlans >= MAX_PLANS) {
            Optional<Stored> oldest = plans.values().stream()
                    .filter(value -> tenantId.equals(value.bundle().tenantId()))
                    .min(java.util.Comparator.comparing(value -> value.plan().createdAt()));
            if (oldest.isEmpty()) {
                break;
            }
            plans.remove(oldest.get().plan().planId());
            tenantPlans--;
        }
        plans.put(stored.plan().planId(), stored);
    }

    private static Map<String, Integer> counts(List<PlanItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            counts.put(status.name(), (int) items.stream().filter(item -> item.status() == status).count());
        }
        counts.put("TOTAL", items.size());
        return counts;
    }

    // ------------------------------------------------------------------ classification

    /** One row with its classification and the stored row it refers to. */
    private record Classified(DefinitionTable table, DefinitionBundle.Row row, PlanItem item, Object existing) {
    }

    private record Evaluation(List<Classified> rows, List<PlanItem> items, List<String> notes,
            int invalidCount) {
    }

    /** Stored definitions a classification compares against, plus what the file will add. */
    private record Snapshot(Map<String, MetaRepository.PlatformRow> platforms,
            Map<String, MetaRepository.MetaSetRow> sets,
            Map<String, MetaRepository.MetaItemRow> items,
            Map<String, BaseProductRepository.BaseProductRow> baseProducts,
            Map<String, BaseProductRepository.BaseFieldRow> baseFields,
            Map<String, ProductRepository.ProductRow> products,
            Map<String, ProductRepository.ProductFieldRow> productFields,
            Set<String> valueTypes, Set<String> roles) {
    }

    private Evaluation evaluate(String tenantId, DefinitionBundle bundle) {
        Snapshot snapshot = snapshot(tenantId);
        List<Classified> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Map<String, String> setTypes = new LinkedHashMap<>();
        snapshot.sets().forEach((code, row) -> setTypes.put(code, row.setType()));
        for (DefinitionBundle.Row row : bundle.rows(DefinitionTable.META_SET)) {
            setTypes.put(row.get("code"), row.get("type", "VALUE").toUpperCase(Locale.ROOT));
        }
        Set<String> plannedPlatforms = new LinkedHashSet<>(snapshot.platforms().keySet());
        bundle.rows(DefinitionTable.PLATFORM).forEach(row -> plannedPlatforms.add(row.get("code")));
        Set<String> plannedSets = new LinkedHashSet<>(snapshot.sets().keySet());
        bundle.rows(DefinitionTable.META_SET).forEach(row -> plannedSets.add(row.get("code")));
        Set<String> plannedBases = new LinkedHashSet<>(snapshot.baseProducts().keySet());
        bundle.rows(DefinitionTable.BASE_PRODUCT).forEach(row -> plannedBases.add(
                row.get("platform_code") + "/" + row.get("code")));
        Set<String> plannedBaseFields = new LinkedHashSet<>(snapshot.baseFields().keySet());
        bundle.rows(DefinitionTable.BASE_FIELD).forEach(row -> plannedBaseFields.add(
                row.get("platform_code") + "/" + row.get("base_code") + "/" + row.get("field_key")));
        Set<String> plannedProducts = new LinkedHashSet<>(snapshot.products().keySet());
        bundle.rows(DefinitionTable.PRODUCT).forEach(row -> plannedProducts.add(row.get("code")));
        Map<String, String> ruleOwners = ruleOwners(snapshot, bundle, setTypes);

        for (DefinitionTable table : bundle.tables().keySet()) {
            Set<String> seen = new HashSet<>();
            for (DefinitionBundle.Row row : bundle.rows(table)) {
                String key = DefinitionKeys.describe(table, row);
                List<String> problems = new ArrayList<>();
                if (!seen.add(key)) {
                    problems.add("该键在文件中重复出现");
                }
                Checked checked;
                try {
                    checked = switch (table) {
                        case PLATFORM -> checkPlatform(row, snapshot, problems);
                        case BASE_PRODUCT -> checkBaseProduct(row, snapshot, plannedPlatforms, problems);
                        case BASE_FIELD -> checkBaseField(row, snapshot, plannedBases, problems);
                        case PRODUCT -> checkProduct(row, snapshot, plannedBases, problems);
                        case PRODUCT_FIELD -> checkProductField(row, snapshot, plannedBases, plannedBaseFields,
                                plannedProducts, problems);
                        case META_SET -> checkMetaSet(row, snapshot, problems);
                        case META_ITEM -> checkMetaItem(row, snapshot, plannedSets, setTypes, ruleOwners, problems);
                    };
                    readNumericCells(table, row);
                } catch (IllegalArgumentException e) {
                    // a cell that cannot be read invalidates only its own row, never the whole file
                    problems.add(e.getMessage() == null ? "该行存在无法解析的单元格" : e.getMessage());
                    checked = new Checked(null, false);
                }
                Status status;
                if (!problems.isEmpty()) {
                    status = Status.INVALID;
                } else if (checked.existing() == null) {
                    status = Status.ADD;
                } else if (checked.differs()) {
                    status = Status.CONFLICT;
                } else {
                    status = Status.UNCHANGED;
                }
                PlanItem item = new PlanItem(table.sheet(), key, status, problems);
                rows.add(new Classified(table, row, item, checked.existing()));
            }
        }
        List<PlanItem> items = rows.stream().map(Classified::item).toList();
        int invalid = (int) items.stream().filter(item -> item.status() == Status.INVALID).count();
        if (bundle.rows(DefinitionTable.BASE_FIELD).isEmpty()
                && bundle.tables().containsKey(DefinitionTable.BASE_FIELD)) {
            notes.add("文件中没有 BASE_FIELD 行，产品的字段不会被定义");
        }
        return new Evaluation(rows, items, notes, invalid);
    }

    /** Classification of one row: the stored counterpart and whether it differs. */
    private record Checked(Object existing, boolean differs) {
    }

    /**
     * Reads the numeric columns of a row, so a text cell is reported at preview time instead of
     * failing when the row is written.
     */
    private static void readNumericCells(DefinitionTable table, DefinitionBundle.Row row) {
        if (table.columns().contains("sort_order")) {
            row.getInt("sort_order", 0);
        }
    }

    private Snapshot snapshot(String tenantId) {
        Map<String, MetaRepository.PlatformRow> platforms = new LinkedHashMap<>();
        meta.platforms().forEach(row -> platforms.put(row.code(), row));
        Map<String, MetaRepository.MetaSetRow> sets = new LinkedHashMap<>();
        meta.sets(tenantId).forEach(row -> sets.put(row.code(), row));
        Map<String, MetaRepository.MetaItemRow> items = new LinkedHashMap<>();
        meta.allItems(tenantId).forEach(row -> items.put(row.setCode() + "/" + row.code(), row));
        Map<String, BaseProductRepository.BaseProductRow> baseProducts = new LinkedHashMap<>();
        bases.listAll(tenantId).forEach(row -> baseProducts.put(row.platformCode() + "/" + row.code(), row));
        Map<String, BaseProductRepository.BaseFieldRow> baseFields = new LinkedHashMap<>();
        for (BaseProductRepository.BaseProductRow base : baseProducts.values()) {
            for (BaseProductRepository.BaseFieldRow field : bases.listFields(tenantId, base.id())) {
                baseFields.put(base.platformCode() + "/" + base.code() + "/" + field.fieldKey(), field);
            }
        }
        Map<String, ProductRepository.ProductRow> products = new LinkedHashMap<>();
        this.products.listAll(tenantId).forEach(row -> products.put(row.code(), row));
        Map<String, ProductRepository.ProductFieldRow> productFields = new LinkedHashMap<>();
        for (ProductRepository.ProductRow product : products.values()) {
            for (ProductRepository.ProductFieldRow field : this.products.listFields(tenantId, product.id())) {
                productFields.put(product.code() + "/" + field.fieldKey(), field);
            }
        }
        Set<String> valueTypes = new LinkedHashSet<>();
        Set<String> roles = new LinkedHashSet<>();
        for (MetaRepository.MetaItemRow item : items.values()) {
            if (MetaLookup.VALUE_TYPE_SET.equals(item.setCode())) {
                valueTypes.add(item.code());
            } else if (MetaLookup.FIELD_ROLE_SET.equals(item.setCode())) {
                roles.add(item.code());
            }
        }
        return new Snapshot(platforms, sets, items, baseProducts, baseFields, products, productFields,
                valueTypes, roles);
    }

    private List<ProductRepository.ProductRow> products(String tenantId) {
        return products.listAll(tenantId);
    }

    /** Rule code to the validation set that owns it, stored rows first and file rows on top. */
    private Map<String, String> ruleOwners(Snapshot snapshot, DefinitionBundle bundle,
            Map<String, String> setTypes) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (MetaRepository.MetaItemRow item : snapshot.items().values()) {
            String type = setTypes.get(item.setCode());
            if (MetaSetType.VALIDATION.name().equals(type)) {
                owners.put(item.code(), item.setCode());
            }
        }
        for (DefinitionBundle.Row row : bundle.rows(DefinitionTable.META_ITEM)) {
            String type = setTypes.get(row.get("set_code"));
            if (MetaSetType.VALIDATION.name().equals(type)) {
                owners.putIfAbsent(row.get("code"), row.get("set_code"));
            }
        }
        return owners;
    }

    private Checked checkPlatform(DefinitionBundle.Row row, Snapshot snapshot, List<String> problems) {
        requireColumns(row, List.of("code", "name"), problems);
        MetaRepository.PlatformRow stored = snapshot.platforms().get(row.get("code"));
        boolean differs = problems.isEmpty() && stored != null && platformDiffers(stored, row);
        return new Checked(stored, differs);
    }

    private Checked checkBaseProduct(DefinitionBundle.Row row, Snapshot snapshot, Set<String> plannedPlatforms,
            List<String> problems) {
        requireColumns(row, List.of("platform_code", "code", "name"), problems);
        if (row.get("platform_code") != null && !plannedPlatforms.contains(row.get("platform_code"))) {
            problems.add("平台 " + row.get("platform_code") + " 不存在");
        }
        BaseProductRepository.BaseProductRow stored =
                snapshot.baseProducts().get(row.get("platform_code") + "/" + row.get("code"));
        boolean differs = problems.isEmpty() && stored != null && baseProductDiffers(stored, row);
        return new Checked(stored, differs);
    }

    private Checked checkBaseField(DefinitionBundle.Row row, Snapshot snapshot, Set<String> plannedBases,
            List<String> problems) {
        requireColumns(row, List.of("platform_code", "base_code", "field_key", "header"), problems);
        String baseKey = row.get("platform_code") + "/" + row.get("base_code");
        if (!plannedBases.contains(baseKey)) {
            problems.add("基产品 " + baseKey + " 不存在");
        }
        requireDictionary(row, snapshot, problems);
        BaseProductRepository.BaseFieldRow stored = snapshot.baseFields().get(baseKey + "/" + row.get("field_key"));
        boolean differs = problems.isEmpty() && stored != null && baseFieldDiffers(stored, row);
        return new Checked(stored, differs);
    }

    private Checked checkProduct(DefinitionBundle.Row row, Snapshot snapshot, Set<String> plannedBases,
            List<String> problems) {
        requireColumns(row, List.of("platform_code", "base_code", "code", "name"), problems);
        String baseKey = row.get("platform_code") + "/" + row.get("base_code");
        if (!plannedBases.contains(baseKey)) {
            problems.add("基产品 " + baseKey + " 不存在");
        }
        ProductRepository.ProductRow stored = snapshot.products().get(row.get("code"));
        if (stored != null && !stored.baseProductCode().equals(row.get("base_code"))) {
            problems.add("产品 " + row.get("code") + " 已挂在基产品 " + stored.platformCode() + "/"
                    + stored.baseProductCode() + "，不能改挂其他基产品");
        }
        boolean differs = problems.isEmpty() && stored != null && productDiffers(stored, row);
        return new Checked(stored, differs);
    }

    private Checked checkProductField(DefinitionBundle.Row row, Snapshot snapshot, Set<String> plannedBases,
            Set<String> plannedBaseFields, Set<String> plannedProducts, List<String> problems) {
        requireColumns(row, List.of("product_code", "field_key", "header"), problems);
        ProductRepository.ProductRow product = snapshot.products().get(row.get("product_code"));
        if (product == null && !plannedProducts.contains(row.get("product_code"))) {
            problems.add("产品 " + row.get("product_code") + " 不存在");
        }
        String baseKey = product == null ? null
                : product.platformCode() + "/" + product.baseProductCode();
        if (baseKey != null && !plannedBaseFields.contains(baseKey + "/" + row.get("field_key"))) {
            problems.add("基产品 " + baseKey + " 未定义字段 " + row.get("field_key"));
        }
        requireDictionary(row, snapshot, problems);
        ProductRepository.ProductFieldRow stored =
                snapshot.productFields().get(row.get("product_code") + "/" + row.get("field_key"));
        boolean differs = problems.isEmpty() && stored != null && productFieldDiffers(stored, row);
        return new Checked(stored, differs);
    }

    private Checked checkMetaSet(DefinitionBundle.Row row, Snapshot snapshot, List<String> problems) {
        requireColumns(row, List.of("code", "name", "type"), problems);
        String type = row.get("type", "VALUE").toUpperCase(Locale.ROOT);
        try {
            MetaSetType.of(type);
        } catch (IllegalArgumentException e) {
            problems.add("集合类型必须是 VALUE / FORMULA / TAG / VALIDATION");
        }
        MetaRepository.MetaSetRow stored = snapshot.sets().get(row.get("code"));
        boolean differs = problems.isEmpty() && stored != null && metaSetDiffers(stored, row);
        return new Checked(stored, differs);
    }

    private Checked checkMetaItem(DefinitionBundle.Row row, Snapshot snapshot, Set<String> plannedSets,
            Map<String, String> setTypes, Map<String, String> ruleOwners, List<String> problems) {
        requireColumns(row, List.of("set_code", "code", "label"), problems);
        String setCode = row.get("set_code");
        if (!plannedSets.contains(setCode)) {
            problems.add("集合 " + setCode + " 不存在");
        }
        String refSet = row.get("ref_set_code");
        if (refSet != null && !plannedSets.contains(refSet)) {
            problems.add("引用集合 " + refSet + " 不存在");
        }
        if (refSet != null && refSet.equals(setCode)) {
            problems.add("取值不能引用自己所在的集合");
        }
        requireRule(row, setTypes.get(setCode), ruleOwners, problems);
        MetaRepository.MetaItemRow stored = snapshot.items().get(setCode + "/" + row.get("code"));
        boolean differs = problems.isEmpty() && stored != null && metaItemDiffers(stored, row);
        if (differs && RequiredMeta.isProtected(setCode, row.get("code"))) {
            problems.add("必须取值不可修改（应用依赖该取值）");
            return new Checked(stored, false);
        }
        return new Checked(stored, differs);
    }

    private static void requireColumns(DefinitionBundle.Row row, List<String> columns, List<String> problems) {
        for (String column : columns) {
            if (row.get(column) == null) {
                problems.add("缺少必填列值 " + column);
            }
        }
    }

    private static void requireDictionary(DefinitionBundle.Row row, Snapshot snapshot, List<String> problems) {
        String valueType = row.get("value_type_code");
        if (valueType != null && !snapshot.valueTypes().contains(valueType)) {
            problems.add("值类型 " + valueType + " 不存在");
        }
        String role = row.get("role_code");
        if (role != null && !snapshot.roles().contains(role)) {
            problems.add("角色类型 " + role + " 不存在");
        }
    }

    private static void requireRule(DefinitionBundle.Row row, String setType, Map<String, String> ruleOwners,
            List<String> problems) {
        if (!MetaSetType.VALIDATION.name().equals(setType)) {
            return;
        }
        String ruleType = row.get("rule_type");
        if (ruleType == null) {
            problems.add("校验规则需要填写 rule_type（REGEX / WILDCARD）");
        } else {
            try {
                ValidationRuleType.of(ruleType);
            } catch (IllegalArgumentException e) {
                problems.add("rule_type 必须是 REGEX 或 WILDCARD");
            }
        }
        String severity = row.get("severity");
        if (severity != null && !severity.isBlank()) {
            String level = severity.trim().toUpperCase(Locale.ROOT);
            if (!ValidationSeverity.WARN.name().equals(level)
                    && !ValidationSeverity.ERROR.name().equals(level)) {
                problems.add("severity 必须是 WARN 或 ERROR");
            }
        }
        String content = row.get("content");
        if (content == null) {
            problems.add("校验规则需要填写表达式（content）");
        } else if (ruleType != null) {
            try {
                ValidationRule.compile(row.get("code"), row.get("label"), ruleType, content,
                        ValidationSeverity.of(severity));
            } catch (RuleSyntaxException e) {
                problems.add("校验表达式无效：" + e.getMessage());
            }
        }
        String owner = ruleOwners.get(row.get("code"));
        if (owner != null && !owner.equals(row.get("set_code"))) {
            problems.add("规则码 " + row.get("code") + " 已被集合 " + owner + " 使用");
        }
    }

    // ------------------------------------------------------------------ field comparison

    private static boolean platformDiffers(MetaRepository.PlatformRow stored, DefinitionBundle.Row row) {
        return !sameText(stored.name(), row.get("name"))
                || !sameText(stored.description(), row.get("description"))
                || stored.sortOrder() != row.getInt("sort_order", 0)
                || stored.enabled() != row.getBoolean("enabled", true);
    }

    private static boolean baseProductDiffers(BaseProductRepository.BaseProductRow stored,
            DefinitionBundle.Row row) {
        return !sameText(stored.name(), row.get("name"))
                || !sameText(stored.description(), row.get("description"))
                || stored.sortOrder() != row.getInt("sort_order", 0);
    }

    private static boolean baseFieldDiffers(BaseProductRepository.BaseFieldRow stored, DefinitionBundle.Row row) {
        return !sameText(stored.header(), row.get("header"))
                || !sameText(stored.displayName(), row.get("display_name"))
                || !stored.aliases().equals(row.getList("aliases"))
                || !sameText(stored.valueTypeCode(), row.get("value_type_code", "TEXT"))
                || !sameText(stored.roleCode(), row.get("role_code", "BUSINESS"))
                || !stored.tags().equals(row.getList("tags"))
                || stored.required() != row.getBoolean("required", false)
                || stored.businessKey() != row.getBoolean("business_key", true)
                || stored.keyFlag() != row.getBoolean("key_flag", false)
                || stored.sortOrder() != row.getInt("sort_order", 0)
                || !sameText(stored.remark(), row.get("remark"))
                || !sameText(stored.formatHint(), row.get("format_hint"))
                || !sameText(stored.clonePattern(), row.get("clone_pattern"))
                || !sameText(stored.validationExpr(), row.get("validation_expr"));
    }

    private static boolean productDiffers(ProductRepository.ProductRow stored, DefinitionBundle.Row row) {
        return !sameText(stored.name(), row.get("name"))
                || !sameText(stored.description(), row.get("description"))
                || stored.strictMatch() != row.getBoolean("strict_match", false);
    }

    private static boolean productFieldDiffers(ProductRepository.ProductFieldRow stored,
            DefinitionBundle.Row row) {
        return !sameText(stored.header(), row.get("header"))
                || !sameText(stored.displayName(), row.get("display_name"))
                || !stored.aliases().equals(row.getList("aliases"))
                || !sameText(stored.valueTypeCode(), row.get("value_type_code", "TEXT"))
                || !sameText(stored.roleCode(), row.get("role_code", "BUSINESS"))
                || !stored.tags().equals(row.getList("tags"))
                || stored.required() != row.getBoolean("required", false)
                || stored.businessKey() != row.getBoolean("business_key", true)
                || stored.keyFlag() != row.getBoolean("key_flag", false)
                || stored.sortOrder() != row.getInt("sort_order", 0)
                || !sameText(stored.remark(), row.get("remark"))
                || !sameText(stored.formatHint(), row.get("format_hint"))
                || !sameText(stored.clonePattern(), row.get("clone_pattern"))
                || !sameText(stored.validationExpr(), row.get("validation_expr"));
    }

    private static boolean metaSetDiffers(MetaRepository.MetaSetRow stored, DefinitionBundle.Row row) {
        return !sameText(stored.name(), row.get("name"))
                || !sameText(stored.description(), row.get("description"))
                || stored.sortOrder() != row.getInt("sort_order", 0)
                || !sameText(stored.setType(), row.get("type", "VALUE"));
    }

    private static boolean metaItemDiffers(MetaRepository.MetaItemRow stored, DefinitionBundle.Row row) {
        return !sameText(stored.label(), row.get("label"))
                || !sameText(stored.refSetCode(), row.get("ref_set_code"))
                || stored.sortOrder() != row.getInt("sort_order", 0)
                || stored.enabled() != row.getBoolean("enabled", true)
                || !sameText(stored.remark(), row.get("remark"))
                || !sameText(stored.content(), row.get("content"))
                || !sameText(stored.ruleType(), row.get("rule_type"))
                || !ValidationSeverity.of(stored.severity()).name()
                        .equals(ValidationSeverity.of(row.get("severity")).name());
    }

    /** Blank and null are the same value, everything else compares as trimmed text. */
    private static boolean sameText(String left, String right) {
        return (left == null ? "" : left.trim()).equals(right == null ? "" : right.trim());
    }

    // ------------------------------------------------------------------ writing

    /** Clears the scope, keeping the meta values the application itself relies on. */
    private int clear(String tenantId, DefinitionScope scope, List<String> notes) {
        int deleted = 0;
        if (scope == DefinitionScope.PRODUCT) {
            for (ProductRepository.ProductRow product : products.listAll(tenantId)) {
                for (ProductRepository.ProductFieldRow field : products.listFields(tenantId, product.id())) {
                    products.deleteField(field.id());
                    deleted++;
                }
                products.delete(product.id());
                deleted++;
            }
            for (BaseProductRepository.BaseProductRow base : bases.listAll(tenantId)) {
                for (BaseProductRepository.BaseFieldRow field : bases.listFields(tenantId, base.id())) {
                    bases.deleteField(field.id());
                    deleted++;
                }
                bases.delete(base.id());
                deleted++;
            }
            return deleted;
        }
        Set<String> protectedSets = new HashSet<>();
        RequiredMeta.SETS.forEach(def -> protectedSets.add(def.code()));
        for (MetaRepository.MetaItemRow item : meta.allItems(tenantId)) {
            if (RequiredMeta.isProtected(item.setCode(), item.code())) {
                notes.add("保留必须取值 " + item.setCode() + "/" + item.code());
                continue;
            }
            meta.deleteItem(tenantId, item.setCode(), item.code());
            deleted++;
        }
        for (MetaRepository.MetaSetRow set : meta.sets(tenantId)) {
            if (protectedSets.contains(set.code())) {
                continue;
            }
            meta.deleteSet(tenantId, set.code());
            deleted++;
        }
        return deleted;
    }

    /** Writes the classified rows and refreshes the schema hashes afterwards. */
    private final class Writer {

        private final String tenantId;
        private final LocalDateTime now = time.nowUtc();
        private int created;
        private int updated;

        private Writer(String tenantId) {
            this.tenantId = tenantId;
        }

        private void create(Classified classified) {
            DefinitionBundle.Row row = classified.row();
            switch (classified.table()) {
                case PLATFORM -> meta.upsertPlatform(platformRow(row));
                case BASE_PRODUCT -> bases.insert(baseProductRow(row));
                case BASE_FIELD -> bases.insertField(baseFieldRow(row));
                case PRODUCT -> products.insert(productRow(row));
                case PRODUCT_FIELD -> products.insertField(productFieldRow(row), now);
                // a full restore keeps the values the application requires, so those rows still exist
                case META_SET -> {
                    if (meta.setExists(tenantId, row.get("code"))) {
                        meta.updateSet(tenantId, row.get("code"), row.get("name", ""), row.get("description"),
                                row.getInt("sort_order", 0),
                                row.get("type", "VALUE").toUpperCase(Locale.ROOT), now);
                    } else {
                        meta.insertSet(tenantId, metaSetRow(row), now);
                    }
                }
                case META_ITEM -> {
                    if (meta.item(tenantId, row.get("set_code"), row.get("code")).isPresent()) {
                        meta.updateItem(tenantId, row.get("set_code"), row.get("code"), row.get("label", ""),
                                row.get("ref_set_code"), row.getInt("sort_order", 0),
                                row.getBoolean("enabled", true), row.get("remark"), row.get("content"),
                                row.get("rule_type"), ValidationSeverity.of(row.get("severity")).name(), now);
                    } else {
                        meta.insertItem(tenantId, metaItemRow(row), now);
                    }
                }
            }
            created++;
        }

        private void update(Classified classified) {
            DefinitionBundle.Row row = classified.row();
            switch (classified.table()) {
                case PLATFORM -> meta.upsertPlatform(platformRow(row));
                case BASE_PRODUCT -> {
                    BaseProductRepository.BaseProductRow stored =
                            (BaseProductRepository.BaseProductRow) classified.existing();
                    bases.update(stored.id(), row.get("name", stored.name()), row.get("description"),
                            row.getInt("sort_order", stored.sortOrder()), now);
                }
                case BASE_FIELD -> {
                    BaseProductRepository.BaseFieldRow stored =
                            (BaseProductRepository.BaseFieldRow) classified.existing();
                    bases.updateField(baseFieldRow(stored.id(), stored.baseProductId(), row), now);
                }
                case PRODUCT -> {
                    ProductRepository.ProductRow stored = (ProductRepository.ProductRow) classified.existing();
                    products.update(stored.id(), row.get("name", stored.name()), row.get("description"),
                            row.getBoolean("strict_match", false), now);
                }
                case PRODUCT_FIELD -> {
                    ProductRepository.ProductFieldRow stored =
                            (ProductRepository.ProductFieldRow) classified.existing();
                    products.updateField(productFieldRow(stored.id(), stored.productId(), row, true), now);
                }
                case META_SET -> {
                    MetaRepository.MetaSetRow stored = (MetaRepository.MetaSetRow) classified.existing();
                    meta.updateSet(tenantId, stored.code(), row.get("name", stored.name()),
                            row.get("description"), row.getInt("sort_order", stored.sortOrder()),
                            row.get("type", stored.setType()).toUpperCase(Locale.ROOT), now);
                }
                case META_ITEM -> {
                    MetaRepository.MetaItemRow stored = (MetaRepository.MetaItemRow) classified.existing();
                    meta.updateItem(tenantId, stored.setCode(), stored.code(), row.get("label", stored.label()),
                            row.get("ref_set_code"), row.getInt("sort_order", stored.sortOrder()),
                            row.getBoolean("enabled", stored.enabled()), row.get("remark"),
                            row.get("content"), row.get("rule_type"),
                            ValidationSeverity.of(row.get("severity")).name(), now);
                }
            }
            updated++;
        }

        private MetaRepository.PlatformRow platformRow(DefinitionBundle.Row row) {
            return new MetaRepository.PlatformRow(row.get("code"), row.get("name", ""), row.get("description"),
                    row.getInt("sort_order", 0), row.getBoolean("enabled", true));
        }

        private BaseProductRepository.BaseProductRow baseProductRow(DefinitionBundle.Row row) {
            String id = Ids.newId();
            return new BaseProductRepository.BaseProductRow(id, tenantId, row.get("platform_code"),
                    row.get("code"), row.get("name", ""), row.get("description"),
                    row.get("status", "ACTIVE"), row.getInt("sort_order", 0), null, now, now);
        }

        private BaseProductRepository.BaseFieldRow baseFieldRow(DefinitionBundle.Row row) {
            String baseId = bases.findByCode(tenantId, row.get("platform_code"), row.get("base_code"))
                    .orElseThrow(() -> ApiException.invalid(
                            "基产品 " + row.get("platform_code") + "/" + row.get("base_code") + " 不存在"))
                    .id();
            return baseFieldRow(Ids.newId(), baseId, row);
        }

        private BaseProductRepository.BaseFieldRow baseFieldRow(String id, String baseId,
                DefinitionBundle.Row row) {
            return new BaseProductRepository.BaseFieldRow(id, tenantId, baseId, row.get("field_key"),
                    row.get("header", ""), row.get("display_name"), row.getList("aliases"),
                    row.get("value_type_code", "TEXT"), row.get("role_code", "BUSINESS"), row.getList("tags"),
                    row.getBoolean("required", false), row.getBoolean("business_key", true),
                    row.getBoolean("key_flag", false), row.getInt("sort_order", 0), row.get("remark"),
                    row.get("format_hint"), row.get("clone_pattern"), row.get("validation_expr"));
        }

        private ProductRepository.ProductRow productRow(DefinitionBundle.Row row) {
            BaseProductRepository.BaseProductRow base = bases
                    .findByCode(tenantId, row.get("platform_code"), row.get("base_code"))
                    .orElseThrow(() -> ApiException.invalid(
                            "基产品 " + row.get("platform_code") + "/" + row.get("base_code") + " 不存在"));
            return new ProductRepository.ProductRow(Ids.newId(), tenantId, base.platformCode(), base.id(),
                    base.code(), row.get("code"), row.get("name", ""), row.get("description"),
                    row.get("status", "ACTIVE"), row.getBoolean("strict_match", false), null, now, now);
        }

        private ProductRepository.ProductFieldRow productFieldRow(DefinitionBundle.Row row) {
            ProductRepository.ProductRow product =
                    products.findByCode(tenantId, row.get("product_code"))
                            .orElseThrow(() -> ApiException.invalid(
                                    "产品 " + row.get("product_code") + " 不存在"));
            String baseFieldId = findBaseFieldId(product, row.get("field_key"));
            return productFieldRow(Ids.newId(), product.id(), baseFieldId, row);
        }

        private ProductRepository.ProductFieldRow productFieldRow(String id, String productId, String baseFieldId,
                DefinitionBundle.Row row) {
            return new ProductRepository.ProductFieldRow(id, tenantId, productId, baseFieldId,
                    row.get("field_key"), row.get("header", ""), row.get("display_name"),
                    row.getList("aliases"), row.get("value_type_code", "TEXT"),
                    row.get("role_code", "BUSINESS"), row.getList("tags"),
                    row.getBoolean("required", false), row.getBoolean("business_key", true),
                    row.getBoolean("key_flag", false), row.getInt("sort_order", 0), row.get("remark"),
                    row.get("format_hint"), row.get("clone_pattern"), row.get("validation_expr"));
        }

        /** Field row of an update: the base field of the same key may have been re-created. */
        private ProductRepository.ProductFieldRow productFieldRow(String id, String productId,
                DefinitionBundle.Row row, boolean refreshBaseField) {
            ProductRepository.ProductRow product = products.findById(tenantId, productId)
                    .orElseThrow(() -> ApiException.invalid("产品 " + productId + " 不存在"));
            String baseFieldId = refreshBaseField
                    ? findBaseFieldId(product, row.get("field_key")) : null;
            return productFieldRow(id, productId, baseFieldId, row);
        }

        private String findBaseFieldId(ProductRepository.ProductRow product, String fieldKey) {
            for (BaseProductRepository.BaseFieldRow field : bases.listFields(tenantId, product.baseProductId())) {
                if (field.fieldKey().equals(fieldKey)) {
                    return field.id();
                }
            }
            throw ApiException.invalid("基产品 " + product.baseProductCode() + " 未定义字段 " + fieldKey);
        }

        private MetaRepository.MetaSetRow metaSetRow(DefinitionBundle.Row row) {
            return new MetaRepository.MetaSetRow(row.get("code"), row.get("name", ""), row.get("description"),
                    row.getBoolean("built_in", false), row.getInt("sort_order", 0),
                    row.get("type", "VALUE").toUpperCase(Locale.ROOT));
        }

        private MetaRepository.MetaItemRow metaItemRow(DefinitionBundle.Row row) {
            return new MetaRepository.MetaItemRow(row.get("set_code"), row.get("code"), row.get("label", ""),
                    row.get("ref_set_code"), row.getInt("sort_order", 0), row.getBoolean("enabled", true),
                    row.get("remark"), row.get("content"), row.get("rule_type"),
                    ValidationSeverity.of(row.get("severity")).name());
        }

        /** Recomputes the fingerprints of every definition, meta values influence them as well. */
        private void refreshSchemaHashes() {
            MetaLookup.Lookup lookup = metaLookup.load(tenantId);
            for (BaseProductRepository.BaseProductRow base : bases.listAll(tenantId)) {
                String hash = schemaMapper
                        .toBaseSchema(base, bases.listFields(tenantId, base.id()), lookup).schemaHash();
                bases.updateSchemaHash(base.id(), hash, now);
            }
            for (ProductRepository.ProductRow product : products.listAll(tenantId)) {
                String hash = schemaMapper
                        .toSchema(product, products.listFields(tenantId, product.id()), lookup).schemaHash();
                products.updateSchemaHash(product.id(), hash, now);
            }
        }
    }
}
