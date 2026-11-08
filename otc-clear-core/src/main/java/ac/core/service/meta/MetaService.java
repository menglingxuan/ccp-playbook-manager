package ac.core.service.meta;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.common.TimeProvider;
import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.MetaRepository;
import ac.core.persistence.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Field meta information: named enumerations used by the field definition dimensions. */
@Service
public class MetaService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");
    public static final String VALUE_TYPE_SET = "VALUE_TYPE";
    public static final String FIELD_ROLE_SET = "FIELD_ROLE";
    public static final String FIELD_TAG_SET = "FIELD_TAG";

    private final MetaRepository meta;
    private final BaseProductRepository baseProducts;
    private final ProductRepository products;
    private final TimeProvider time;

    public MetaService(MetaRepository meta, BaseProductRepository baseProducts, ProductRepository products,
            TimeProvider time) {
        this.meta = meta;
        this.baseProducts = baseProducts;
        this.products = products;
        this.time = time;
    }

    /**
     * Meta set summary.
     *
     * @param code      set code
     * @param name      display name
     * @param description description
     * @param builtIn   true for the sets the application relies on
     * @param sortOrder display order
     * @param itemCount number of items
     */
    public record SetView(String code, String name, String description, boolean builtIn, int sortOrder,
            long itemCount) {
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
     */
    public record ItemView(String code, String label, String refSetCode, int sortOrder, boolean enabled,
            String remark) {
    }

    public List<SetView> sets(String tenantId) {
        Map<String, Long> counts = meta.itemCounts(tenantId);
        List<SetView> views = new ArrayList<>();
        for (MetaRepository.MetaSetRow row : meta.sets(tenantId)) {
            views.add(new SetView(row.code(), row.name(), row.description(), row.builtIn(), row.sortOrder(),
                    counts.getOrDefault(row.code(), 0L)));
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
    public SetView createSet(String tenantId, String code, String name, String description, Integer sortOrder) {
        String normalized = requireCode(code, "Set code");
        if (meta.setExists(tenantId, normalized)) {
            throw new ApiException(ErrorCode.CONFLICT, "Meta set '" + normalized + "' already exists");
        }
        int order = sortOrder == null || sortOrder <= 0 ? meta.sets(tenantId).size() + 1 : sortOrder;
        meta.insertSet(tenantId, new MetaRepository.MetaSetRow(normalized, requireText(name, "Set name"),
                description, false, order), time.nowUtc());
        return new SetView(normalized, name, description, false, order, 0);
    }

    @Transactional
    public void updateSet(String tenantId, String code, String name, String description, Integer sortOrder) {
        MetaRepository.MetaSetRow row = requireSet(tenantId, code);
        int order = sortOrder == null || sortOrder <= 0 ? row.sortOrder() : sortOrder;
        meta.updateSet(tenantId, code, requireText(name, "Set name"), description, order, time.nowUtc());
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
        meta.deleteSet(tenantId, code);
    }

    @Transactional
    public ItemView createItem(String tenantId, String setCode, String code, String label, String refSetCode,
            Integer sortOrder, String remark) {
        requireSet(tenantId, setCode);
        String normalized = requireCode(code, "Item code");
        if (meta.item(tenantId, setCode, normalized).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Item '" + normalized + "' already exists in set '" + setCode + "'");
        }
        String ref = normalizeRef(tenantId, refSetCode, setCode);
        int order = sortOrder == null || sortOrder <= 0
                ? meta.items(tenantId, setCode).size() + 1 : sortOrder;
        requireFreeOrder(tenantId, setCode, order, null);
        meta.insertItem(tenantId, new MetaRepository.MetaItemRow(setCode, normalized,
                requireText(label, "Item label"), ref, order, true, remark), time.nowUtc());
        return new ItemView(normalized, label, ref, order, true, remark);
    }

    /**
     * Updates an item; {@code null} order or enable flag keeps the stored value.
     */
    @Transactional
    public void updateItem(String tenantId, String setCode, String code, String label, String refSetCode,
            Integer sortOrder, Boolean enabled, String remark) {
        requireSet(tenantId, setCode);
        MetaRepository.MetaItemRow row = meta.item(tenantId, setCode, code)
                .orElseThrow(() -> ApiException.notFound(
                        "Item '" + code + "' does not exist in set '" + setCode + "'"));
        String ref = normalizeRef(tenantId, refSetCode, setCode);
        int order = sortOrder == null || sortOrder <= 0 ? row.sortOrder() : sortOrder;
        requireFreeOrder(tenantId, setCode, order, code);
        boolean on = enabled == null ? row.enabled() : enabled;
        meta.updateItem(tenantId, setCode, code, requireText(label, "Item label"), ref, order, on,
                remark, time.nowUtc());
    }

    /** Rejects an order already used by another item of the same set. */
    private void requireFreeOrder(String tenantId, String setCode, int sortOrder, String currentCode) {
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
        long usage = countFieldUsage(tenantId, setCode, code);
        if (usage > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Item '" + code + "' is used by " + usage + " field definition(s)",
                    Map.of("usage", usage));
        }
        meta.deleteItem(tenantId, setCode, code);
    }

    public MetaRepository.MetaSetRow requireSet(String tenantId, String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return meta.set(tenantId, normalized)
                .orElseThrow(() -> ApiException.notFound("Meta set '" + code + "' does not exist"));
    }

    public boolean itemEnabled(String tenantId, String setCode, String code) {
        return meta.item(tenantId, setCode, code).filter(MetaRepository.MetaItemRow::enabled).isPresent();
    }

    private long countFieldUsage(String tenantId, String setCode, String code) {
        boolean byValueType = VALUE_TYPE_SET.equals(setCode);
        boolean byRole = FIELD_ROLE_SET.equals(setCode);
        if (!byValueType && !byRole) {
            return 0;
        }
        long usage = 0;
        for (BaseProductRepository.BaseProductRow base : baseProducts.listAll(tenantId)) {
            for (BaseProductRepository.BaseFieldRow field : baseProducts.listFields(tenantId, base.id())) {
                if (code.equals(byValueType ? field.valueTypeCode() : field.roleCode())) {
                    usage++;
                }
            }
        }
        for (ProductRepository.ProductRow product : products.listAll(tenantId)) {
            for (ProductRepository.ProductFieldRow field : products.listFields(tenantId, product.id())) {
                if (code.equals(byValueType ? field.valueTypeCode() : field.roleCode())) {
                    usage++;
                }
            }
        }
        return usage;
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
                row.remark());
    }

    public static String requireCode(String value, String what) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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
