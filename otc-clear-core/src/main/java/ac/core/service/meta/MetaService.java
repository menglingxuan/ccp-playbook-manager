package ac.core.service.meta;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.common.TimeProvider;
import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.MetaRepository;
import ac.core.persistence.ProductRepository;
import ac.core.service.audit.AuditModule;
import ac.core.service.audit.AuditService;
import ac.domain.validation.RuleExpression;
import ac.domain.validation.RuleSyntaxException;
import ac.domain.validation.ValidationOutcome;
import ac.domain.validation.ValidationRule;
import ac.domain.validation.ValidationRuleType;
import ac.domain.validation.ValidationSeverity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Field meta information: named enumerations used by the field definition dimensions. */
@Service
public class MetaService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final int CONTENT_LENGTH = 512;
    /** Sample values accepted by the rule test endpoint. */
    private static final int MAX_SAMPLE_TEXT_LENGTH = 4_000;
    public static final String VALUE_TYPE_SET = "VALUE_TYPE";
    public static final String FIELD_ROLE_SET = "FIELD_ROLE";
    public static final String FIELD_TAG_SET = "FIELD_TAG";

    private final MetaRepository meta;
    private final BaseProductRepository baseProducts;
    private final ProductRepository products;
    private final TimeProvider time;
    private final AuditService audit;

    public MetaService(MetaRepository meta, BaseProductRepository baseProducts, ProductRepository products,
            TimeProvider time, AuditService audit) {
        this.meta = meta;
        this.baseProducts = baseProducts;
        this.products = products;
        this.time = time;
        this.audit = audit;
    }

    /**
     * Meta set summary.
     *
     * @param code      set code
     * @param name      display name
     * @param description description
     * @param builtIn   true for the sets the application relies on
     * @param sortOrder display order
     * @param type      {@code VALUE} or {@code FORMULA}
     * @param itemCount number of items
     */
    public record SetView(String code, String name, String description, boolean builtIn, int sortOrder,
            String type, long itemCount) {
    }

    /**
     * Meta item view.
     *
     * @param code       item code stored on fields
     * @param label      display label
     * @param refSetCode referenced set when the item acts as an enumeration reference
     * @param sortOrder  display order
     * @param enabled    only enabled items are selectable
     * @param remark     free form note
     * @param content    value text of a VALUE set, the formula of a FORMULA set or the pattern of a
     *                   VALIDATION set
     * @param ruleType   {@code REGEX} or {@code WILDCARD} for the pattern of a VALIDATION set
     * @param severity   {@code WARN} or {@code ERROR} for a rule that rejects a value
     * @param readonly   true when the application requires this value, so it cannot be edited or deleted
     */
    public record ItemView(String code, String label, String refSetCode, int sortOrder, boolean enabled,
            String remark, String content, String ruleType, String severity, boolean readonly) {
    }

    public List<SetView> sets(String tenantId) {
        Map<String, Long> counts = meta.itemCounts(tenantId);
        List<SetView> views = new ArrayList<>();
        for (MetaRepository.MetaSetRow row : meta.sets(tenantId)) {
            views.add(new SetView(row.code(), row.name(), row.description(), row.builtIn(), row.sortOrder(),
                    row.setType(), counts.getOrDefault(row.code(), 0L)));
        }
        return views;
    }

    public List<ItemView> items(String tenantId, String setCode) {
        requireSet(tenantId, setCode);
        return meta.items(tenantId, setCode).stream().map(this::toView).toList();
    }

    /** Options grouped by set, used by the field editor. */
    public Map<String, List<ItemView>> optionMap(String tenantId) {
        Map<String, List<ItemView>> options = new LinkedHashMap<>();
        for (MetaRepository.MetaItemRow item : meta.allItems(tenantId)) {
            if (!item.enabled()) {
                continue;
            }
            options.computeIfAbsent(item.setCode(), key -> new ArrayList<>()).add(toView(item));
        }
        return options;
    }

    @Transactional
    public SetView createSet(String tenantId, String code, String name, String description, Integer sortOrder,
            String type) {
        String normalized = requireCode(code, "Set code");
        if (meta.setExists(tenantId, normalized)) {
            throw new ApiException(ErrorCode.CONFLICT, "Meta set '" + normalized + "' already exists");
        }
        int order = sortOrder == null || sortOrder <= 0 ? meta.sets(tenantId).size() + 1 : sortOrder;
        String setType = requireSetType(type).name();
        meta.insertSet(tenantId, new MetaRepository.MetaSetRow(normalized, requireText(name, "Set name"),
                description, false, order, setType), time.nowUtc());
        audit.record(tenantId, AuditModule.META_DICTIONARY, AuditService.ENTITY_META_SET, normalized, "CREATE",
                Map.of("name", name, "type", setType));
        return new SetView(normalized, name, description, false, order, setType, 0);
    }

    @Transactional
    public void updateSet(String tenantId, String code, String name, String description, Integer sortOrder,
            String type) {
        MetaRepository.MetaSetRow row = requireSet(tenantId, code);
        int order = sortOrder == null || sortOrder <= 0 ? row.sortOrder() : sortOrder;
        String setType = type == null || type.isBlank() ? row.setType() : requireSetType(type).name();
        meta.updateSet(tenantId, code, requireText(name, "Set name"), description, order, setType,
                time.nowUtc());
        audit.record(tenantId, AuditModule.META_DICTIONARY, AuditService.ENTITY_META_SET, row.code(), "UPDATE",
                Map.of("name", name, "type", setType));
    }

    @Transactional
    public void deleteSet(String tenantId, String code) {
        MetaRepository.MetaSetRow row = requireSet(tenantId, code);
        if (row.builtIn()) {
            throw ApiException.invalid("Built-in meta set '" + code + "' cannot be deleted");
        }
        List<String> referencing = new ArrayList<>();
        for (MetaRepository.MetaItemRow item : meta.allItems(tenantId)) {
            if (code.equals(item.refSetCode())) {
                referencing.add(item.setCode() + "." + item.code());
            }
        }
        if (!referencing.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Meta set '" + code + "' is referenced by " + String.join(", ", referencing),
                    Map.of("referencing", referencing));
        }
        List<String> used = new ArrayList<>();
        Map<String, Long> usageMap = fieldUsage(tenantId);
        boolean byRule = isValidationSet(tenantId, code);
        for (MetaRepository.MetaItemRow item : meta.items(tenantId, code)) {
            long usage = usageOf(usageMap, code, item.code(), byRule);
            if (usage > 0) {
                used.add(item.code() + " (" + usage + ")");
            }
        }
        if (!used.isEmpty()) {
            // validation rules are referenced by field expressions, so deleting the set would leave them dangling
            throw new ApiException(ErrorCode.CONFLICT,
                    "Meta set '" + code + "' is used by field definitions: " + String.join(", ", used),
                    Map.of("usage", used));
        }
        meta.deleteSet(tenantId, code);
        audit.record(tenantId, AuditModule.META_DICTIONARY, AuditService.ENTITY_META_SET, code, "DELETE",
                Map.of("name", row.name()));
    }

    @Transactional
    public ItemView createItem(String tenantId, String setCode, String code, String label, String refSetCode,
            Integer sortOrder, String remark, String content, String ruleType, String severity) {
        MetaRepository.MetaSetRow set = requireSet(tenantId, setCode);
        String normalized = requireCode(code, "Item code");
        if (meta.item(tenantId, setCode, normalized).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Item '" + normalized + "' already exists in set '" + setCode + "'");
        }
        if (MetaSetType.VALIDATION.name().equals(set.setType())) {
            requireRuleCodeIsFree(tenantId, setCode, normalized);
        }
        String ref = normalizeRef(tenantId, refSetCode, setCode);
        String itemContent = requireContent(set, content);
        String type = requireRuleType(set, itemContent, ruleType);
        String level = requireSeverity(set, severity);
        int order = sortOrder == null || sortOrder <= 0
                ? meta.items(tenantId, setCode).size() + 1 : sortOrder;
        requireFreeOrder(tenantId, setCode, order, null);
        meta.insertItem(tenantId, new MetaRepository.MetaItemRow(setCode, normalized,
                requireText(label, "Item label"), ref, order, true, remark, itemContent, type, level),
                time.nowUtc());
        audit.record(tenantId, AuditModule.META_DICTIONARY, AuditService.ENTITY_META_ITEM,
                setCode + "/" + normalized, "CREATE", Map.of("label", label, "setCode", setCode));
        return new ItemView(normalized, label, ref, order, true, remark, itemContent, type, level,
                RequiredMeta.isProtected(setCode, normalized));
    }

    /**
     * Updates an item; {@code null} order or enable flag keeps the stored value.
     */
    @Transactional
    public void updateItem(String tenantId, String setCode, String code, String label, String refSetCode,
            Integer sortOrder, Boolean enabled, String remark, String content, String ruleType,
            String severity) {
        MetaRepository.MetaSetRow set = requireSet(tenantId, setCode);
        MetaRepository.MetaItemRow row = meta.item(tenantId, setCode, code)
                .orElseThrow(() -> ApiException.notFound(
                        "Item '" + code + "' does not exist in set '" + setCode + "'"));
        requireEditable(setCode, code);
        String ref = normalizeRef(tenantId, refSetCode, setCode);
        int order = sortOrder == null || sortOrder <= 0 ? row.sortOrder() : sortOrder;
        requireFreeOrder(tenantId, setCode, order, code);
        boolean on = enabled == null ? row.enabled() : enabled;
        String itemContent = requireContent(set, content);
        String type = requireRuleType(set, itemContent, ruleType == null ? row.ruleType() : ruleType);
        String level = severity == null || severity.isBlank()
                ? normalizedSeverity(row.severity()) : requireSeverity(set, severity);
        meta.updateItem(tenantId, setCode, code, requireText(label, "Item label"), ref, order, on,
                remark, itemContent, type, level, time.nowUtc());
        audit.record(tenantId, AuditModule.META_DICTIONARY, AuditService.ENTITY_META_ITEM,
                setCode + "/" + row.code(), "UPDATE",
                Map.of("label", label, "enabled", on, "setCode", setCode));
    }

    /**
     * Refuses edits of the values the application itself relies on.
     *
     * @throws ApiException conflict when the value is one of {@link RequiredMeta}
     */
    private void requireEditable(String setCode, String code) {
        if (RequiredMeta.isProtected(setCode, code)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Value '" + code + "' of set '" + setCode
                            + "' is required by the product definition and cannot be edited or deleted",
                    Map.of("setCode", setCode, "code", code));
        }
    }

    /** Rejects an order already used by another item of the same set. */    private void requireFreeOrder(String tenantId, String setCode, int sortOrder, String currentCode) {
        for (MetaRepository.MetaItemRow item : meta.items(tenantId, setCode)) {
            if (item.sortOrder() == sortOrder && !item.code().equals(currentCode)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Order " + sortOrder + " is already used by another item",
                        Map.of("sortOrder", sortOrder));
            }
        }
    }

    @Transactional
    public void deleteItem(String tenantId, String setCode, String code) {
        requireSet(tenantId, setCode);
        requireEditable(setCode, code);
        long usage = countFieldUsage(tenantId, setCode, code);
        if (usage > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Item '" + code + "' is used by " + usage + " field definition(s)",
                    Map.of("usage", usage));
        }
        meta.deleteItem(tenantId, setCode, code);
        audit.record(tenantId, AuditModule.META_DICTIONARY, AuditService.ENTITY_META_ITEM,
                setCode + "/" + code, "DELETE", Map.of("setCode", setCode));
    }

    public MetaRepository.MetaSetRow requireSet(String tenantId, String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return meta.set(tenantId, normalized)
                .orElseThrow(() -> ApiException.notFound("Meta set '" + code + "' does not exist"));
    }

    public boolean itemEnabled(String tenantId, String setCode, String code) {
        return meta.item(tenantId, setCode, code).filter(MetaRepository.MetaItemRow::enabled).isPresent();
    }

    /**
     * Meta set each value type references, keyed by value type code.
     *
     * <p>A field whose value type references a set is already validated against that set by the type
     * layer, so the rule screens count it as covered instead of reporting it as unruled. The whole set
     * is read at once, because the callers ask for every field of a product.
     */
    public Map<String, String> valueTypeSets(String tenantId) {
        Map<String, String> sets = new LinkedHashMap<>();
        for (MetaRepository.MetaItemRow item : meta.items(tenantId, VALUE_TYPE_SET)) {
            if (item.refSetCode() != null && !item.refSetCode().isBlank()) {
                sets.put(item.code(), item.refSetCode());
            }
        }
        return sets;
    }

    private long countFieldUsage(String tenantId, String setCode, String code) {
        Map<String, Long> usage = fieldUsage(tenantId);
        boolean byRule = isValidationSet(tenantId, setCode);
        Long direct = byRule ? null : usage.get(setCode + "/" + code);
        long total = direct == null ? 0 : direct;
        if (byRule) {
            Long referenced = usage.get("*" + code);
            total += referenced == null ? 0 : referenced;
        }
        return total;
    }

    /** Reference count of one item inside an already computed usage map. */
    private static long usageOf(Map<String, Long> usage, String setCode, String code, boolean byRule) {
        Long direct = byRule ? null : usage.get(setCode + "/" + code);
        long total = direct == null ? 0 : direct;
        if (byRule) {
            Long referenced = usage.get("*" + code);
            total += referenced == null ? 0 : referenced;
        }
        return total;
    }

    private boolean isValidationSet(String tenantId, String setCode) {
        return meta.set(tenantId, setCode)
                .map(row -> MetaSetType.VALIDATION.name().equals(row.setType()))
                .orElse(false);
    }

    /**
     * Field references of every meta item of a tenant, keyed by {@code setCode/itemCode}.
     *
     * <p>One pass over all base and product fields serves the whole request: the set/item delete paths
     * check every item they hold, and a per item scan multiplied the field reads by the item count.
     */
    private Map<String, Long> fieldUsage(String tenantId) {
        Map<String, Long> usage = new LinkedHashMap<>();
        Set<String> validationSets = new LinkedHashSet<>();
        for (MetaRepository.MetaSetRow set : meta.sets(tenantId)) {
            if (MetaSetType.VALIDATION.name().equals(set.setType())) {
                validationSets.add(set.code());
            }
        }
        List<FieldReference> fields = new ArrayList<>();
        for (BaseProductRepository.BaseProductRow base : baseProducts.listAll(tenantId)) {
            for (BaseProductRepository.BaseFieldRow field : baseProducts.listFields(tenantId, base.id())) {
                fields.add(new FieldReference(field.valueTypeCode(), field.roleCode(), field.validationExpr()));
            }
        }
        for (ProductRepository.ProductRow product : products.listAll(tenantId)) {
            for (ProductRepository.ProductFieldRow field : products.listFields(tenantId, product.id())) {
                fields.add(new FieldReference(field.valueTypeCode(), field.roleCode(), field.validationExpr()));
            }
        }
        for (FieldReference field : fields) {
            if (field.valueTypeCode() != null) {
                usage.merge(VALUE_TYPE_SET + "/" + field.valueTypeCode(), 1L, Long::sum);
            }
            if (field.roleCode() != null) {
                usage.merge(FIELD_ROLE_SET + "/" + field.roleCode(), 1L, Long::sum);
            }
            if (field.validationExpr() == null || field.validationExpr().isBlank()) {
                continue;
            }
            try {
                for (String referenced : RuleExpression.parse(field.validationExpr()).codes()) {
                    // a rule code is unique across the validation sets, so the set is not needed here
                    if (!validationSets.isEmpty()) {
                        usage.merge("*" + referenced, 1L, Long::sum);
                    }
                }
            } catch (RuleSyntaxException e) {
                // a broken expression cannot reference anything reliably
            }
        }
        return usage;
    }

    /** Values of a field that can reference a meta item. */
    private record FieldReference(String valueTypeCode, String roleCode, String validationExpr) {
    }

    private String normalizeRef(String tenantId, String refSetCode, String ownSetCode) {
        if (refSetCode == null || refSetCode.isBlank()) {
            return null;
        }
        String ref = refSetCode.trim().toUpperCase(Locale.ROOT);
        if (ref.equals(ownSetCode)) {
            throw ApiException.invalid("A meta item cannot reference its own set");
        }
        if (!meta.setExists(tenantId, ref)) {
            throw ApiException.invalid("Referenced meta set '" + ref + "' does not exist");
        }
        return ref;
    }

    private ItemView toView(MetaRepository.MetaItemRow row) {
        return new ItemView(row.code(), row.label(), row.refSetCode(), row.sortOrder(), row.enabled(),
                row.remark(), row.content(), row.ruleType(), normalizedSeverity(row.severity()),
                RequiredMeta.isProtected(row.setCode(), row.code()));
    }

    /**
     * Validates the severity of a rule; any other set stores the default.
     *
     * <p>Only a rule of a {@link MetaSetType#VALIDATION} set can reject a value, so the level is
     * meaningless elsewhere and is normalised away.
     */
    private String requireSeverity(MetaRepository.MetaSetRow set, String severity) {
        if (!MetaSetType.VALIDATION.name().equals(set.setType())
                || severity == null || severity.isBlank()) {
            return ValidationSeverity.WARN.name();
        }
        String level = severity.trim().toUpperCase(Locale.ROOT);
        if (!ValidationSeverity.WARN.name().equals(level) && !ValidationSeverity.ERROR.name().equals(level)) {
            throw ApiException.invalid("Severity must be WARN or ERROR");
        }
        return level;
    }

    private static String normalizedSeverity(String severity) {
        return ValidationSeverity.of(severity).name();
    }

    /** Parses the requested set type, rejecting unknown codes with a 400. */
    public static MetaSetType requireSetType(String value) {
        try {
            return MetaSetType.of(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("Set type must be VALUE, FORMULA, TAG or VALIDATION");
        }
    }

    /**
     * Distinct tag values of every {@link MetaSetType#TAG} set, ignoring the set they belong to.
     *
     * <p>Batch editing offers these as suggestions; free tags stay allowed. Only enabled items are
     * returned and the tag text is the item content when present, otherwise its label.
     */
    public List<String> tagOptions(String tenantId) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (MetaRepository.MetaSetRow set : meta.sets(tenantId)) {
            if (!MetaSetType.TAG.name().equals(set.setType())) {
                continue;
            }
            for (MetaRepository.MetaItemRow item : meta.items(tenantId, set.code())) {
                if (!item.enabled()) {
                    continue;
                }
                String tag = tagText(item);
                if (!tag.isEmpty()) {
                    byKey.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
                }
            }
        }
        return List.copyOf(byKey.values());
    }

    /** Tag text of one item: its content, falling back to the label and then the code. */
    private static String tagText(MetaRepository.MetaItemRow item) {
        for (String candidate : new String[] {item.content(), item.label(), item.code()}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    /**
     * Normalizes the content of an item.
     *
     * <p>Formula sets need a formula; value and tag sets accept any text. The content is optional so
     * older items can keep relying on their code.
     */
    private static String requireContent(MetaRepository.MetaSetRow set, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (MetaSetType.FORMULA.name().equals(set.setType())) {
            if (!text.startsWith("=")) {
                throw ApiException.invalid("A formula of set '" + set.code() + "' must start with '='");
            }
            if (text.length() > CONTENT_LENGTH) {
                throw ApiException.invalid("The formula exceeds " + CONTENT_LENGTH + " characters");
            }
            return text;
        }
        return text.length() > CONTENT_LENGTH ? text.substring(0, CONTENT_LENGTH) : text;
    }

    /**
     * Validates the pattern of a rule item and normalizes its type.
     *
     * @return {@code null} outside validation sets, otherwise {@code REGEX} or {@code WILDCARD}
     */
    private static String requireRuleType(MetaRepository.MetaSetRow set, String content, String ruleType) {
        if (!MetaSetType.VALIDATION.name().equals(set.setType())) {
            return null;
        }
        String normalized = ValidationRuleType.of(ruleType).name();
        try {
            ValidationRule.compile("rule", null, normalized, content);
        } catch (RuleSyntaxException e) {
            throw ApiException.invalid(e.getMessage());
        }
        return normalized;
    }

    /** Rule codes are referenced by field expressions, so they must be unique across all rule sets. */
    private void requireRuleCodeIsFree(String tenantId, String setCode, String code) {
        for (MetaRepository.MetaSetRow set : meta.sets(tenantId)) {
            if (!MetaSetType.VALIDATION.name().equals(set.setType()) || set.code().equals(setCode)) {
                continue;
            }
            if (meta.item(tenantId, set.code(), code).isPresent()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Rule code '" + code + "' is already used by set '" + set.code() + "'");
            }
        }
    }

    /**
     * Compiles every enabled rule of the tenant, keyed by rule code.
     *
     * <p>A rule that no longer compiles is skipped: the field that references it then reports the
     * value as failing instead of breaking the page.
     */
    public Map<String, ValidationRule> rules(String tenantId) {
        Map<String, ValidationRule> rules = new LinkedHashMap<>();
        for (MetaRepository.MetaSetRow set : meta.sets(tenantId)) {
            if (!MetaSetType.VALIDATION.name().equals(set.setType())) {
                continue;
            }
            for (MetaRepository.MetaItemRow item : meta.items(tenantId, set.code())) {
                if (!item.enabled()) {
                    continue;
                }
                try {
                    rules.put(item.code(), ValidationRule.compile(item.code(), item.label(),
                            item.ruleType(), item.content(), ValidationSeverity.of(item.severity())));
                } catch (RuleSyntaxException e) {
                    // ignore a broken definition, the field level check reports the value
                }
            }
        }
        return rules;
    }

    /**
     * Checks one field expression against the stored rules.
     *
     * @param expression rule codes combined with {@code AND}, {@code OR}, {@code NOT} and parentheses
     * @param text       value to check
     * @return {@code valid} for the expression syntax, {@code matched} for the value
     */
    public RuleTest testExpression(String tenantId, String expression, String text) {
        RuleExpression parsed;
        try {
            parsed = RuleExpression.parse(expression);
        } catch (RuleSyntaxException e) {
            return new RuleTest(false, false, e.getMessage(), List.of());
        }
        Map<String, ValidationRule> rules = rules(tenantId);
        List<String> unknown = parsed.codes().stream().filter(code -> !rules.containsKey(code)).toList();
        if (!unknown.isEmpty()) {
            return new RuleTest(false, false, "Unknown rule code(s): " + String.join(", ", unknown),
                    unknown);
        }
        ValidationOutcome outcome = ValidationOutcome.evaluate(parsed, text, rules);
        return new RuleTest(true, outcome.passed(), null, outcome.failedRules());
    }

    /**
     * Result of testing one expression.
     *
     * @param valid    true when the expression parses and every referenced rule exists
     * @param matched  true when the tested value satisfies the expression
     * @param message  error text when {@code valid} is false
     * @param problems failing (or unknown) rule codes
     */
    public record RuleTest(boolean valid, boolean matched, String message, List<String> problems) {        public RuleTest {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    /**
     * Compiles one pattern rule and matches it against a sample text, used by the rule editor so the
     * user sees whether the pattern is valid and what it accepts before saving.
     *
     * @param ruleType   {@code REGEX} or {@code WILDCARD}
     * @param expression the pattern text
     * @param text       sample value, {@code null} is treated as an empty value
     */
    public PatternTest testPattern(String ruleType, String expression, String text) {
        ValidationRule rule;
        try {
            rule = ValidationRule.compile("_TEST", null, ruleType, expression);
        } catch (RuleSyntaxException e) {
            return new PatternTest(false, false, e.getMessage());
        }
        if (text == null || text.isBlank()) {
            return new PatternTest(true, false, "blank values are skipped by validation");
        }
        if (text.length() > MAX_SAMPLE_TEXT_LENGTH) {
            // the editor is a sandbox: a huge sample must not turn into minutes of matcher time
            return new PatternTest(true, false, "Sample text is limited to " + MAX_SAMPLE_TEXT_LENGTH
                    + " characters; the value shown here would be " + text.length());
        }
        return new PatternTest(true, rule.matches(text), null);
    }

    /**
     * Result of testing one pattern.
     *
     * @param valid   true when the pattern compiles
     * @param matched true when the sample text matches the whole pattern
     * @param message hint or error text
     */
    public record PatternTest(boolean valid, boolean matched, String message) {
    }

    /** Suggestions for the field editor: every enabled rule of the tenant. */
    public List<ItemView> ruleOptions(String tenantId) {
        List<ItemView> options = new ArrayList<>();
        for (MetaRepository.MetaSetRow set : meta.sets(tenantId)) {
            if (!MetaSetType.VALIDATION.name().equals(set.setType())) {
                continue;
            }
            for (MetaRepository.MetaItemRow item : meta.items(tenantId, set.code())) {
                if (item.enabled()) {
                    options.add(toView(item));
                }
            }
        }
        return options;
    }

    public static String requireCode(String value, String what) {        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw ApiException.invalid(what + " must match " + CODE_PATTERN.pattern());
        }
        return normalized;
    }

    public static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalid(what + " is required");
        }
        return value.trim();
    }

    /** Timestamp used by the definition service as well. */
    public LocalDateTime now() {
        return time.nowUtc();
    }
}
