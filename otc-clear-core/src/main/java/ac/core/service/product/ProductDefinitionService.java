package ac.core.service.product;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.common.Ids;
import ac.core.common.TimeProvider;
import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.BaseProductRepository.BaseFieldRow;
import ac.core.persistence.BaseProductRepository.BaseProductRow;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.MetaRepository;
import ac.core.persistence.ProductRepository;
import ac.core.persistence.ProductRepository.ProductFieldRow;
import ac.core.persistence.ProductRepository.ProductRow;
import ac.core.persistence.TagCodec;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.audit.AuditService;
import ac.core.service.meta.MetaService;
import ac.core.service.product.ProductViews.BaseFieldView;
import ac.core.service.product.ProductViews.BaseProductDetail;
import ac.core.service.product.ProductViews.BaseProductSummary;
import ac.core.service.product.ProductViews.PlatformView;
import ac.core.service.product.ProductViews.ProductDetail;
import ac.core.service.product.ProductViews.ProductFieldView;
import ac.core.service.product.ProductViews.ProductSummary;
import ac.domain.product.BaseProductCode;
import ac.domain.product.FieldRole;
import ac.domain.product.PlatformCode;
import ac.domain.product.ProductSchema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product definition management: platform to base product to concrete product.
 *
 * <p>A base product owns the complete field list; a product inherits it and may only drop fields,
 * extend the required flag, reorder fields and adjust tags or remarks.
 */
@Slf4j
@Service
public class ProductDefinitionService {

    private static final Pattern PRODUCT_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_.-]{0,63}");

    /** Value type used when columns are imported straight from a file header. */
    private static final String VALUE_TYPE_TEXT = "TEXT";

    private final BaseProductRepository baseRepository;
    private final ProductRepository productRepository;
    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final MetaRepository metaRepository;
    private final MetaService metaService;
    private final MetaLookup metaLookup;
    private final ProductSchemaMapper schemaMapper;
    private final ProductCatalog catalog;
    private final TimeProvider time;
    private final AuditService audit;

    public ProductDefinitionService(BaseProductRepository baseRepository, ProductRepository productRepository,
            ImportBatchRepository batchRepository, TestCaseRepository caseRepository,
            MetaRepository metaRepository, MetaService metaService, MetaLookup metaLookup,
            ProductSchemaMapper schemaMapper, ProductCatalog catalog, TimeProvider time, AuditService audit) {
        this.baseRepository = baseRepository;
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.metaRepository = metaRepository;
        this.metaService = metaService;
        this.metaLookup = metaLookup;
        this.schemaMapper = schemaMapper;
        this.catalog = catalog;
        this.time = time;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ queries

    public List<PlatformView> platforms(String tenantId) {
        Map<String, List<BaseProductSummary>> byPlatform = new LinkedHashMap<>();
        for (BaseProductRow base : baseRepository.listAll(tenantId)) {
            byPlatform.computeIfAbsent(base.platformCode(), key -> new ArrayList<>())
                    .add(toSummary(tenantId, base));
        }
        List<PlatformView> views = new ArrayList<>();
        for (PlatformCode platform : PlatformCode.values()) {
            List<BaseProductSummary> items = byPlatform.getOrDefault(platform.name(), List.of());
            views.add(new PlatformView(platform.name(), platform.displayName(), "", items));
        }
        return views;
    }

    public BaseProductDetail baseProductDetail(String tenantId, String baseProductId) {
        BaseProductRow base = requireBase(tenantId, baseProductId);
        MetaLookup.Lookup lookup = metaLookup.load(tenantId);
        List<BaseFieldView> fields = baseRepository.listFields(tenantId, baseProductId).stream()
                .map(field -> toBaseFieldView(field, lookup)).toList();
        List<ProductSummary> products = productRepository.listByBaseProduct(tenantId, baseProductId).stream()
                .map(row -> toSummary(tenantId, row)).toList();
        return new BaseProductDetail(toSummary(tenantId, base), fields, products);
    }

    public ProductDetail productDetail(String tenantId, String productId) {
        ProductRow product = requireProduct(tenantId, productId);
        MetaLookup.Lookup lookup = metaLookup.load(tenantId);
        Map<String, BaseFieldRow> baseFields = new LinkedHashMap<>();
        for (BaseFieldRow field : baseRepository.listFields(tenantId, product.baseProductId())) {
            baseFields.put(field.id(), field);
        }
        List<ProductFieldView> fields = productRepository.listFields(tenantId, productId).stream()
                .map(field -> toProductFieldView(field, baseFields.get(field.baseFieldId()), lookup)).toList();
        return new ProductDetail(toSummary(tenantId, product), fields);
    }

    // ------------------------------------------------------------ base products

    /**
     * Creates a base product for the platform. Codes are free form so each platform can carry its own
     * catalogue (for example {@code NB IRS} style codes on CFETS); the seeded codes are only
     * suggestions.
     */
    @Transactional
    public BaseProductSummary createBaseProduct(String tenantId, String platformCode, String baseCode,
            String name, String description) {
        PlatformCode platform = requirePlatform(platformCode);
        String code = requireCode(baseCode, "Base product code");
        if (baseRepository.findByCode(tenantId, platform.name(), code).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Base product " + platform.name() + "/" + code + " already exists");
        }
        int sortOrder = (int) baseRepository.countByPlatform(tenantId, platform.name()) + 1;
        String displayName = name == null || name.isBlank()
                ? BaseProductCode.find(code).map(BaseProductCode::displayName).orElse(code) : name.trim();
        LocalDateTime now = time.nowUtc();
        BaseProductRow row = new BaseProductRow(Ids.newId(), tenantId, platform.name(), code,
                displayName, description, "ACTIVE", sortOrder, null, now, now);
        baseRepository.insert(row);
        refreshBaseHash(tenantId, row.id());
        audit.record(tenantId, "BASE_PRODUCT", row.id(), "CREATE",
                Map.of("platform", platform.name(), "code", code));
        return toSummary(tenantId, requireBase(tenantId, row.id()));
    }

    /**
     * Creates one text/business/required field per file header, appending them in file order.
     * Headers already present on the base product are skipped so the import stays idempotent; a
     * header that appears twice inside the file yields two fields, exactly like the source file.
     *
     * @return field keys that were created
     */
    @Transactional
    public List<String> importBaseFieldsFromHeaders(String tenantId, String baseProductId,
            List<String> headers) {
        requireBase(tenantId, baseProductId);
        if (headers == null || headers.isEmpty()) {
            throw ApiException.invalid("At least one column header is required");
        }
        Set<String> existing = new LinkedHashSet<>();
        for (BaseFieldRow field : baseRepository.listFields(tenantId, baseProductId)) {
            existing.add(field.header().trim().toLowerCase(Locale.ROOT));
        }
        int order = baseRepository.maxFieldOrder(baseProductId);
        List<String> created = new ArrayList<>();
        for (String raw : headers) {
            String header = raw == null ? "" : raw.trim();
            if (header.isEmpty() || existing.contains(header.toLowerCase(Locale.ROOT))) {
                continue;
            }
            order++;
            String fieldKey = suggestFieldKey(header, baseProductId, null);
            BaseFieldRow row = new BaseFieldRow(Ids.newId(), tenantId, baseProductId, fieldKey, header,
                    header, List.of(), VALUE_TYPE_TEXT, FieldRole.BUSINESS.name(), List.of(), true, true,
                    false, order, null, "", "");
            baseRepository.insertField(row);
            created.add(fieldKey);
        }
        refreshBaseHash(tenantId, baseProductId);
        audit.record(tenantId, "BASE_PRODUCT", baseProductId, "IMPORT_FIELDS",
                Map.of("headers", headers.size(), "created", created.size()));
        return created;
    }

    @Transactional
    public void updateBaseProduct(String tenantId, String baseProductId, String name, String description,
            Integer sortOrder) {
        BaseProductRow base = requireBase(tenantId, baseProductId);
        int order = sortOrder == null ? base.sortOrder() : sortOrder;
        baseRepository.update(baseProductId, MetaService.requireText(name, "Base product name"), description,
                order, time.nowUtc());
        audit.record(tenantId, "BASE_PRODUCT", baseProductId, "UPDATE", Map.of("name", name));
    }

    @Transactional
    public void deleteBaseProduct(String tenantId, String baseProductId) {
        requireBase(tenantId, baseProductId);
        long products = productRepository.countByBaseProduct(baseProductId);
        if (products > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Base product still has " + products + " product(s); delete them first",
                    Map.of("productCount", products));
        }
        baseRepository.delete(baseProductId);
        audit.record(tenantId, "BASE_PRODUCT", baseProductId, "DELETE", Map.of());
    }

    // ---------------------------------------------------------------- base fields

    @Transactional
    public BaseFieldView addBaseField(String tenantId, String baseProductId, FieldRequest request) {
        requireBase(tenantId, baseProductId);
        MetaLookup.Lookup lookup = metaLookup.load(tenantId);
        FieldSpec spec = validateField(tenantId, request, lookup, baseProductId, null);

        int order = request.sortOrder() == null
                ? baseRepository.maxFieldOrder(baseProductId) + 1 : request.sortOrder();
        requireFreeOrder(tenantId, baseProductId, order, null);
        BaseFieldRow row = new BaseFieldRow(Ids.newId(), tenantId, baseProductId, spec.fieldKey(),
                spec.header(), request.displayName(), spec.aliases(), spec.valueTypeCode(), spec.roleCode(),
                spec.tags(), request.required(), true, false, order, request.remark(), "", "");
        baseRepository.insertField(row);
        refreshBaseHash(tenantId, baseProductId);
        audit.record(tenantId, "BASE_FIELD", row.id(), "CREATE",
                Map.of("baseProductId", baseProductId, "fieldKey", row.fieldKey()));
        return toBaseFieldView(row, lookup);
    }

    @Transactional
    public BaseFieldView updateBaseField(String tenantId, String baseProductId, String fieldId,
            FieldRequest request) {
        requireBase(tenantId, baseProductId);
        BaseFieldRow existing = baseRepository.findField(tenantId, baseProductId, fieldId)
                .orElseThrow(() -> ApiException.notFound("Field " + fieldId + " does not exist"));
        MetaLookup.Lookup lookup = metaLookup.load(tenantId);
        FieldSpec spec = validateField(tenantId, request, lookup, baseProductId, existing);
        int order = request.sortOrder() == null ? existing.sortOrder() : request.sortOrder();
        requireFreeOrder(tenantId, baseProductId, order, existing.id());

        BaseFieldRow updated = new BaseFieldRow(existing.id(), tenantId, baseProductId, spec.fieldKey(),
                spec.header(), request.displayName(), spec.aliases(), spec.valueTypeCode(), spec.roleCode(),
                spec.tags(), request.required(), existing.businessKey(), existing.keyFlag(), order,
                request.remark(), existing.formatHint(), existing.clonePattern());
        baseRepository.updateField(updated, time.nowUtc());
        propagateBaseField(tenantId, updated);
        refreshBaseHash(tenantId, baseProductId);
        audit.record(tenantId, "BASE_FIELD", fieldId, "UPDATE", Map.of("fieldKey", updated.fieldKey()));
        return toBaseFieldView(updated, lookup);
    }

    @Transactional
    public int deleteBaseField(String tenantId, String baseProductId, String fieldId) {
        requireBase(tenantId, baseProductId);
        BaseFieldRow existing = baseRepository.findField(tenantId, baseProductId, fieldId)
                .orElseThrow(() -> ApiException.notFound("Field " + fieldId + " does not exist"));
        baseRepository.deleteField(fieldId);
        int removedCopies = productRepository.deleteFieldsByBaseField(fieldId);
        for (ProductRow product : productRepository.listByBaseProduct(tenantId, baseProductId)) {
            renumberProductFields(tenantId, product.id());
            refreshProductHash(tenantId, product.id());
        }
        refreshBaseHash(tenantId, baseProductId);
        audit.record(tenantId, "BASE_FIELD", fieldId, "DELETE",
                Map.of("fieldKey", existing.fieldKey(), "productCopies", removedCopies));
        return removedCopies;
    }

    @Transactional
    public void reorderBaseFields(String tenantId, String baseProductId, List<String> fieldIds) {
        requireBase(tenantId, baseProductId);
        List<BaseFieldRow> fields = baseRepository.listFields(tenantId, baseProductId);
        Set<String> known = new LinkedHashSet<>();
        fields.forEach(field -> known.add(field.id()));
        if (!known.containsAll(fieldIds) || fieldIds.size() != known.size()) {
            throw ApiException.invalid("Field list does not match the base product fields");
        }
        LocalDateTime now = time.nowUtc();
        int order = 0;
        for (String id : fieldIds) {
            order++;
            baseRepository.updateFieldOrder(id, order, now);
        }
        refreshBaseHash(tenantId, baseProductId);
    }

    // ----------------------------------------------------------------- products

    @Transactional
    public ProductSummary createProduct(String tenantId, String baseProductId, String code, String name,
            String description, List<String> excludedFieldKeys) {
        BaseProductRow base = requireBase(tenantId, baseProductId);
        String productCode = requireProductCode(code);
        if (productRepository.existsByCode(tenantId, productCode)) {
            throw new ApiException(ErrorCode.CONFLICT, "Product code '" + productCode + "' already exists");
        }
        Set<String> excluded = new LinkedHashSet<>(excludedFieldKeys == null ? List.of() : excludedFieldKeys);

        LocalDateTime now = time.nowUtc();
        ProductRow row = new ProductRow(Ids.newId(), tenantId, base.platformCode(), base.id(), base.code(),
                productCode, MetaService.requireText(name, "Product name"), description, "ACTIVE", false, null,
                now, now);
        productRepository.insert(row);

        int order = 0;
        for (BaseFieldRow field : baseRepository.listFields(tenantId, baseProductId)) {
            if (excluded.contains(field.fieldKey())) {
                continue;
            }
            order++;
            productRepository.insertField(copyOf(tenantId, row.id(), field, order), now);
        }
        refreshProductHash(tenantId, row.id());
        audit.record(tenantId, "PRODUCT", row.id(), "CREATE",
                Map.of("baseProductId", baseProductId, "code", productCode));
        return toSummary(tenantId, requireProduct(tenantId, row.id()));
    }

    @Transactional
    public void updateProduct(String tenantId, String productId, String name, String description,
            Boolean strictMatch) {
        ProductRow product = requireProduct(tenantId, productId);
        String newName = name == null || name.isBlank() ? product.name() : name.trim();
        String newDescription = description == null ? product.description() : description;
        boolean strict = strictMatch == null ? product.strictMatch() : strictMatch;
        productRepository.update(productId, MetaService.requireText(newName, "Product name"), newDescription,
                strict, time.nowUtc());
        refreshProductHash(tenantId, productId);
        audit.record(tenantId, "PRODUCT", productId, "UPDATE",
                Map.of("name", newName, "strictMatch", strict));
    }

    @Transactional
    public void deleteProduct(String tenantId, String productId) {
        ProductRow product = requireProduct(tenantId, productId);
        long batches = batchRepository.countByProduct(tenantId, product.code());
        long cases = caseRepository.countByProduct(tenantId, product.code());
        if (batches > 0 || cases > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Product '" + product.code() + "' is referenced by " + batches + " batch(es) and "
                            + cases + " case(s)",
                    Map.of("batches", batches, "cases", cases));
        }
        productRepository.delete(productId);
        catalog.invalidate(tenantId, product.code());
        audit.record(tenantId, "PRODUCT", productId, "DELETE", Map.of("code", product.code()));
    }

    @Transactional
    public ProductFieldView updateProductField(String tenantId, String productId, String fieldId,
            ProductFieldUpdate update) {
        ProductRow product = requireProduct(tenantId, productId);
        ProductFieldRow existing = productRepository.findField(tenantId, productId, fieldId)
                .orElseThrow(() -> ApiException.notFound("Field " + fieldId + " does not exist"));
        BaseFieldRow baseField = baseRepository.findField(tenantId, product.baseProductId(),
                existing.baseFieldId()).orElse(null);
        if (!isBusinessRole(existing.roleCode()) && baseField != null && baseField.required()
                && !update.required()) {
            throw ApiException.invalid("Field '" + existing.fieldKey()
                    + "' is required by the base product and cannot be relaxed");
        }

        MetaLookup.Lookup lookup = metaLookup.load(tenantId);
        validateTags(tenantId, update.tags());
        int order = update.sortOrder() <= 0 ? existing.sortOrder() : update.sortOrder();
        requireFreeProductOrder(tenantId, productId, order, existing.id());

        ProductFieldRow updated = new ProductFieldRow(existing.id(), tenantId, productId,
                existing.baseFieldId(), existing.fieldKey(), existing.header(), existing.displayName(),
                existing.aliases(), existing.valueTypeCode(), existing.roleCode(), update.tags(),
                isBusinessRole(existing.roleCode()) || update.required(), existing.businessKey(),
                existing.keyFlag(), order, update.remark(), existing.formatHint(), existing.clonePattern());
        productRepository.updateField(updated, time.nowUtc());
        renumberProductFields(tenantId, productId);
        refreshProductHash(tenantId, productId);
        audit.record(tenantId, "PRODUCT_FIELD", fieldId, "UPDATE",
                Map.of("productId", productId, "fieldKey", existing.fieldKey()));
        return toProductFieldView(updated, baseField, lookup);
    }

    @Transactional
    public void deleteProductField(String tenantId, String productId, String fieldId) {
        requireProduct(tenantId, productId);
        ProductFieldRow existing = productRepository.findField(tenantId, productId, fieldId)
                .orElseThrow(() -> ApiException.notFound("Field " + fieldId + " does not exist"));
        productRepository.deleteField(fieldId);
        renumberProductFields(tenantId, productId);
        refreshProductHash(tenantId, productId);
        audit.record(tenantId, "PRODUCT_FIELD", fieldId, "DELETE",
                Map.of("productId", productId, "fieldKey", existing.fieldKey()));
    }

    /**
     * Stores a product specific field order. The product keeps its own numbering; the underlying base
     * product definition is not touched, and the field set remains a subset of the base product.
     */
    @Transactional
    public ProductDetail reorderProductFields(String tenantId, String productId, List<String> fieldIds) {
        ProductRow product = requireProduct(tenantId, productId);
        List<ProductFieldRow> fields = productRepository.listFields(tenantId, productId);
        Set<String> known = new LinkedHashSet<>();
        fields.forEach(field -> known.add(field.id()));
        if (fieldIds == null || !known.containsAll(fieldIds) || fieldIds.size() != known.size()) {
            throw ApiException.invalid("Field list does not match the product fields");
        }
        LocalDateTime now = time.nowUtc();
        int order = 0;
        for (String id : fieldIds) {
            order++;
            productRepository.updateFieldOrder(id, order, now);
        }
        refreshProductHash(tenantId, productId);
        audit.record(tenantId, "PRODUCT", product.id(), "REORDER", Map.of("fieldCount", fieldIds.size()));
        return productDetail(tenantId, productId);
    }

    /**
     * Re-aligns a product with its base product: adds missing base fields when requested, drops copies
     * whose base field no longer exists and refreshes inherited attributes.
     */
    @Transactional
    public ProductDetail syncProduct(String tenantId, String productId, boolean addMissing) {
        ProductRow product = requireProduct(tenantId, productId);
        List<BaseFieldRow> baseFields = baseRepository.listFields(tenantId, product.baseProductId());
        Map<String, ProductFieldRow> byBaseField = new LinkedHashMap<>();
        for (ProductFieldRow field : productRepository.listFields(tenantId, productId)) {
            byBaseField.put(field.baseFieldId(), field);
        }
        LocalDateTime now = time.nowUtc();
        Set<String> baseIds = new LinkedHashSet<>();
        int order = 0;
        for (ProductFieldRow field : byBaseField.values()) {
            order = Math.max(order, field.sortOrder());
        }
        for (BaseFieldRow baseField : baseFields) {
            baseIds.add(baseField.id());
            ProductFieldRow existing = byBaseField.get(baseField.id());
            if (existing == null) {
                if (addMissing) {
                    order++;
                    productRepository.insertField(copyOf(tenantId, productId, baseField, order), now);
                }
            } else {
                propagateToCopy(existing, baseField, now);
            }
        }
        for (Map.Entry<String, ProductFieldRow> entry : byBaseField.entrySet()) {
            if (!baseIds.contains(entry.getKey())) {
                productRepository.deleteField(entry.getValue().id());
            }
        }
        renumberProductFields(tenantId, productId);
        refreshProductHash(tenantId, productId);
        audit.record(tenantId, "PRODUCT", productId, "SYNC", Map.of("addMissing", addMissing));
        return productDetail(tenantId, productId);
    }

    // -------------------------------------------------------------------- helpers

    private record FieldSpec(String fieldKey, String header, List<String> aliases, String valueTypeCode,
            String roleCode, List<String> tags) {
    }

    private FieldSpec validateField(String tenantId, FieldRequest request, MetaLookup.Lookup lookup,
            String baseProductId, BaseFieldRow existing) {
        String header = MetaService.requireText(request.header(), "Field header");
        String valueTypeCode = MetaService.requireCode(request.valueTypeCode(), "Value type");
        String roleCode = MetaService.requireCode(request.roleCode(), "Role type");
        if (!lookup.hasValueType(valueTypeCode)) {
            throw ApiException.invalid("Unknown value type '" + valueTypeCode + "'");
        }
        if (!lookup.roleLabels().containsKey(roleCode)) {
            throw ApiException.invalid("Unknown role type '" + roleCode + "'");
        }
        validateTags(tenantId, request.tags());

        String fieldKey = request.fieldKey() == null || request.fieldKey().isBlank()
                ? suggestFieldKey(header, baseProductId, existing == null ? null : existing.fieldKey())
                : request.fieldKey().trim();
        if (!fieldKey.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw ApiException.invalid("Field key must start with a letter and contain only letters, "
                    + "digits and underscore");
        }
        if (existing == null || !fieldKey.equals(existing.fieldKey())) {
            if (baseRepository.fieldKeyExists(baseProductId, fieldKey)) {
                throw new ApiException(ErrorCode.CONFLICT, "Field key '" + fieldKey + "' already exists");
            }
        }

        return new FieldSpec(fieldKey, header, TagCodec.normalize(request.aliases()), valueTypeCode, roleCode,
                TagCodec.normalize(request.tags()));
    }

    private void validateTags(String tenantId, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        for (String tag : tags) {
            if (!metaService.itemEnabled(tenantId, MetaService.FIELD_TAG_SET, tag)) {
                throw ApiException.invalid("Unknown tag '" + tag + "'");
            }
        }
    }

    private String suggestFieldKey(String header, String baseProductId, String currentKey) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : header.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upperNext = false;
            } else {
                upperNext = sb.length() > 0;
            }
        }
        String baseKey = sb.length() == 0 ? "field" : sb.toString();
        if (baseKey.equals(currentKey)) {
            return baseKey;
        }
        String candidate = baseKey;
        int suffix = 2;
        while (baseRepository.fieldKeyExists(baseProductId, candidate) && !candidate.equals(currentKey)) {
            candidate = baseKey + suffix;
            suffix++;
        }
        return candidate;
    }

    private void propagateBaseField(String tenantId, BaseFieldRow baseField) {
        for (ProductRow product : productRepository.listByBaseProduct(tenantId, baseField.baseProductId())) {
            for (ProductFieldRow copy : productRepository.listFields(tenantId, product.id())) {
                if (baseField.id().equals(copy.baseFieldId())) {
                    propagateToCopy(copy, baseField, time.nowUtc());
                    refreshProductHash(tenantId, product.id());
                }
            }
        }
    }

    private void propagateToCopy(ProductFieldRow copy, BaseFieldRow baseField, LocalDateTime now) {
        boolean required = isBusinessRole(baseField.roleCode()) || baseField.required() || copy.required();
        ProductFieldRow updated = new ProductFieldRow(copy.id(), copy.tenantId(), copy.productId(),
                baseField.id(), baseField.fieldKey(), baseField.header(), baseField.displayName(),
                baseField.aliases(), baseField.valueTypeCode(), baseField.roleCode(), copy.tags(), required,
                baseField.businessKey(), baseField.keyFlag(), copy.sortOrder(), copy.remark(),
                baseField.formatHint(), baseField.clonePattern());
        productRepository.updateField(updated, now);
    }

    private ProductFieldRow copyOf(String tenantId, String productId, BaseFieldRow baseField, int sortOrder) {
        return new ProductFieldRow(Ids.newId(), tenantId, productId, baseField.id(), baseField.fieldKey(),
                baseField.header(), baseField.displayName(), baseField.aliases(), baseField.valueTypeCode(),
                baseField.roleCode(), baseField.tags(),
                isBusinessRole(baseField.roleCode()) || baseField.required(), baseField.businessKey(),
                baseField.keyFlag(), sortOrder, baseField.remark(), baseField.formatHint(),
                baseField.clonePattern());
    }

    /** Business columns are always required in a derived product; managed columns stay configurable. */
    private static boolean isBusinessRole(String roleCode) {
        return FieldRole.BUSINESS.name().equalsIgnoreCase(roleCode == null ? "" : roleCode);
    }

    /** Rejects an order already used by another base product field. */
    private void requireFreeOrder(String tenantId, String baseProductId, int sortOrder, String fieldId) {
        for (BaseFieldRow field : baseRepository.listFields(tenantId, baseProductId)) {
            if (field.sortOrder() == sortOrder && !field.id().equals(fieldId)) {
                throw orderTaken(sortOrder);
            }
        }
    }

    /** Rejects an order already used by another product field. */
    private void requireFreeProductOrder(String tenantId, String productId, int sortOrder, String fieldId) {
        for (ProductFieldRow field : productRepository.listFields(tenantId, productId)) {
            if (field.sortOrder() == sortOrder && !field.id().equals(fieldId)) {
                throw orderTaken(sortOrder);
            }
        }
    }

    private static ApiException orderTaken(int sortOrder) {
        return new ApiException(ErrorCode.CONFLICT, "Order " + sortOrder + " is already used by another field",
                Map.of("sortOrder", sortOrder));
    }

    /** Compacts the product field order to 1..N, keeping the current relative order. */
    private void renumberProductFields(String tenantId, String productId) {
        List<ProductFieldRow> fields = new ArrayList<>(productRepository.listFields(tenantId, productId));
        fields.sort(java.util.Comparator.comparingInt(ProductFieldRow::sortOrder));
        LocalDateTime now = time.nowUtc();
        int order = 0;
        for (ProductFieldRow field : fields) {
            order++;
            if (field.sortOrder() != order) {
                productRepository.updateFieldOrder(field.id(), order, now);
            }
        }
    }

    private void refreshBaseHash(String tenantId, String baseProductId) {
        BaseProductRow base = requireBase(tenantId, baseProductId);
        ProductSchema schema = schemaMapper.toBaseSchema(base,
                baseRepository.listFields(tenantId, baseProductId), metaLookup.load(tenantId));
        baseRepository.updateSchemaHash(baseProductId, schema.schemaHash(), time.nowUtc());
    }

    private void refreshProductHash(String tenantId, String productId) {
        ProductRow product = requireProduct(tenantId, productId);
        ProductSchema schema = schemaMapper.toSchema(product,
                productRepository.listFields(tenantId, productId), metaLookup.load(tenantId));
        productRepository.updateSchemaHash(productId, schema.schemaHash(), time.nowUtc());
        catalog.invalidate(tenantId, product.code());
    }

    private BaseProductRow requireBase(String tenantId, String baseProductId) {
        return baseRepository.findById(tenantId, baseProductId)
                .orElseThrow(() -> ApiException.notFound("Base product " + baseProductId + " does not exist"));
    }

    private ProductRow requireProduct(String tenantId, String productId) {
        return productRepository.findById(tenantId, productId)
                .orElseThrow(() -> ApiException.notFound("Product " + productId + " does not exist"));
    }

    private static PlatformCode requirePlatform(String value) {
        return PlatformCode.find(value)
                .orElseThrow(() -> ApiException.invalid("Unknown platform '" + value + "'"));
    }

    private static String requireProductCode(String value) {
        return requireCode(value, "Product code");
    }

    /** Normalizes a code and validates it against {@link #PRODUCT_CODE}. */
    private static String requireCode(String value, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PRODUCT_CODE.matcher(normalized).matches()) {
            throw ApiException.invalid(label + " must match " + PRODUCT_CODE.pattern());
        }
        return normalized;
    }

    private BaseProductSummary toSummary(String tenantId, BaseProductRow row) {
        long fieldCount = baseRepository.listFields(tenantId, row.id()).size();
        long productCount = productRepository.countByBaseProduct(row.id());
        return new BaseProductSummary(row.id(), row.platformCode(), row.code(), row.name(), row.description(),
                row.sortOrder(), fieldCount, productCount, row.schemaHash());
    }

    private ProductSummary toSummary(String tenantId, ProductRow row) {
        long fieldCount = productRepository.listFields(tenantId, row.id()).size();
        long caseCount = caseRepository.countByProduct(tenantId, row.code());
        return new ProductSummary(row.id(), row.platformCode(), row.baseProductCode(), row.code(), row.name(),
                row.description(), row.status(), row.strictMatch(), (int) fieldCount, caseCount,
                row.schemaHash());
    }

    private BaseFieldView toBaseFieldView(BaseFieldRow field, MetaLookup.Lookup lookup) {
        MetaLookup.ValueTypeSpec spec = lookup.valueType(field.valueTypeCode());
        return new BaseFieldView(field.id(), field.fieldKey(), field.header(), field.displayName(),
                field.aliases(), field.valueTypeCode(), spec.label(), field.roleCode(),
                lookup.roleLabels().getOrDefault(field.roleCode(), field.roleCode()), field.tags(),
                field.required(), field.sortOrder(), field.remark());
    }

    private ProductFieldView toProductFieldView(ProductFieldRow field, BaseFieldRow baseField,
            MetaLookup.Lookup lookup) {
        MetaLookup.ValueTypeSpec spec = lookup.valueType(field.valueTypeCode());
        int baseSortOrder = baseField == null ? field.sortOrder() : baseField.sortOrder();
        return new ProductFieldView(field.id(), field.baseFieldId(), field.fieldKey(), field.header(),
                field.displayName(), field.aliases(), field.valueTypeCode(), spec.label(), field.roleCode(),
                lookup.roleLabels().getOrDefault(field.roleCode(), field.roleCode()), field.tags(),
                field.required(), baseField != null && baseField.required(), field.sortOrder(), baseSortOrder,
                field.remark());
    }

    /** Starter codes offered as suggestions when adding a base product to a platform. */
    public List<String> suggestedBaseCodes() {
        List<String> codes = new ArrayList<>();
        for (BaseProductCode code : BaseProductCode.all()) {
            codes.add(code.name());
        }
        return codes;
    }

    /** Raw meta items, exposed for the definition screens. */
    public List<MetaRepository.MetaItemRow> rawItems(String tenantId) {
        return metaRepository.allItems(tenantId);
    }
}
