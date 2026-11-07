package ac.web.api;

import ac.core.service.meta.MetaService;
import ac.web.config.TenantProvider;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Field meta information endpoints: the enumerations backing the field definition dimensions.
 *
 * <p>Sets other than the built-in ones can be added freely; a set may reference another set so a
 * value type can point at an enumeration such as {@code CONV_METHOD}.
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final MetaService meta;
    private final TenantProvider tenantProvider;

    public MetaController(MetaService meta, TenantProvider tenantProvider) {
        this.meta = meta;
        this.tenantProvider = tenantProvider;
    }

    /** All sets with their items, ordered by the set sort order. */
    @GetMapping("/sets")
    public List<SetWithItems> sets() {
        String tenantId = tenantProvider.tenantId();
        Map<String, List<MetaService.ItemView>> options = meta.optionMap(tenantId);
        return meta.sets(tenantId).stream()
                .map(set -> new SetWithItems(set, options.getOrDefault(set.code(), List.of())))
                .toList();
    }

    /** Flat option map used by the field editor selects. */
    @GetMapping("/options")
    public Map<String, List<MetaService.ItemView>> options() {
        return meta.optionMap(tenantProvider.tenantId());
    }

    @PostMapping("/sets")
    public MetaService.SetView createSet(@RequestBody SetRequest request) {
        return meta.createSet(tenantProvider.tenantId(), request.code(), request.name(), request.description(),
                request.sortOrder() == null ? 0 : request.sortOrder());
    }

    @PatchMapping("/sets/{code}")
    public List<MetaService.SetView> updateSet(@PathVariable String code, @RequestBody SetRequest request) {
        meta.updateSet(tenantProvider.tenantId(), code, request.name(), request.description(),
                request.sortOrder() == null ? 0 : request.sortOrder());
        return meta.sets(tenantProvider.tenantId());
    }

    @DeleteMapping("/sets/{code}")
    public Map<String, Object> deleteSet(@PathVariable String code) {
        meta.deleteSet(tenantProvider.tenantId(), code);
        return Map.of("deleted", true);
    }

    @PostMapping("/sets/{code}/items")
    public MetaService.ItemView createItem(@PathVariable String code, @RequestBody ItemRequest request) {
        return meta.createItem(tenantProvider.tenantId(), code, request.code(), request.label(),
                request.refSetCode(), request.sortOrder() == null ? 0 : request.sortOrder(), request.remark());
    }

    @PatchMapping("/sets/{code}/items/{itemCode}")
    public MetaService.ItemView updateItem(@PathVariable String code, @PathVariable String itemCode,
            @RequestBody ItemRequest request) {
        meta.updateItem(tenantProvider.tenantId(), code, itemCode, request.label(), request.refSetCode(),
                request.sortOrder() == null ? 0 : request.sortOrder(), request.enabled(), request.remark());
        return meta.items(tenantProvider.tenantId(), code).stream()
                .filter(item -> item.code().equals(itemCode))
                .findFirst()
                .orElseThrow();
    }

    @DeleteMapping("/sets/{code}/items/{itemCode}")
    public Map<String, Object> deleteItem(@PathVariable String code, @PathVariable String itemCode) {
        meta.deleteItem(tenantProvider.tenantId(), code, itemCode);
        return Map.of("deleted", true);
    }

    /**
     * A meta set together with its items.
     *
     * @param set   set header
     * @param items items of the set
     */
    public record SetWithItems(MetaService.SetView set, List<MetaService.ItemView> items) {

        public SetWithItems {
            items = List.copyOf(items);
        }
    }

    /**
     * Meta set payload.
     *
     * @param code        set code
     * @param name        display name
     * @param description free form note
     * @param sortOrder   display order
     */
    public record SetRequest(String code, String name, String description, Integer sortOrder) {
    }

    /**
     * Meta item payload.
     *
     * @param code        item code
     * @param label       display label
     * @param refSetCode  set referenced by this item, used by value types
     * @param sortOrder   display order
     * @param enabled     whether the item can be selected
     * @param remark      free form note
     */
    public record ItemRequest(String code, String label, String refSetCode, Integer sortOrder,
            boolean enabled, String remark) {
    }
}
