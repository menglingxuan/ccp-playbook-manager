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

    /**
     * Predefined tags gathered from every {@code TAG} set, used as batch tag suggestions.
     *
     * <p>Set names are irrelevant here: the batch editor offers the values of all tag sets at once and
     * still accepts free tags.
     */
    @GetMapping("/tags")
    public List<String> tags() {
        return meta.tagOptions(tenantProvider.tenantId());
    }

    /** Items of one set, used by the bulk update dialog when a set is picked. */
    @GetMapping("/sets/{code}/items")
    public List<MetaService.ItemView> items(@PathVariable String code) {
        return meta.items(tenantProvider.tenantId(), code);
    }

    @PostMapping("/sets")
    public MetaService.SetView createSet(@RequestBody SetRequest request) {
        return meta.createSet(tenantProvider.tenantId(), request.code(), request.name(), request.description(),
                request.sortOrder(), request.type());
    }

    @PatchMapping("/sets/{code}")
    public List<MetaService.SetView> updateSet(@PathVariable String code, @RequestBody SetRequest request) {
        meta.updateSet(tenantProvider.tenantId(), code, request.name(), request.description(),
                request.sortOrder(), request.type());
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
                request.refSetCode(), request.sortOrder(), request.remark(), request.content(),
                request.ruleType(), request.severity());
    }

    @PatchMapping("/sets/{code}/items/{itemCode}")
    public MetaService.ItemView updateItem(@PathVariable String code, @PathVariable String itemCode,
            @RequestBody ItemRequest request) {
        meta.updateItem(tenantProvider.tenantId(), code, itemCode, request.label(), request.refSetCode(),
                request.sortOrder(), request.enabled(), request.remark(), request.content(),
                request.ruleType(), request.severity());
        return meta.items(tenantProvider.tenantId(), code).stream()
                .filter(item -> item.code().equals(itemCode))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Every enabled validation rule of the tenant, used by the field editors as suggestions.
     *
     * <p>Rule codes are unique across the validation sets, so a field expression only needs the code.
     */
    @GetMapping("/rules")
    public List<MetaService.ItemView> rules() {
        return meta.ruleOptions(tenantProvider.tenantId());
    }

    /**
     * Compiles and tests one rule expression against a sample value.
     *
     * @param request expression and sample text
     */
    @PostMapping("/rules/test")
    public MetaService.RuleTest testExpression(@RequestBody RuleTestRequest request) {
        return meta.testExpression(tenantProvider.tenantId(), request.expression(), request.text());
    }

    /**
     * Compiles one pattern rule and matches it against a sample value, used by the rule editor.
     *
     * @param request rule type, pattern and sample text
     */
    @PostMapping("/rules/match")
    public MetaService.PatternTest testPattern(@RequestBody PatternMatchRequest request) {
        return meta.testPattern(request.ruleType(), request.expression(), request.text());
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
     * @param sortOrder   display order; {@code null} appends the set at the end
     * @param type        {@code VALUE}, {@code FORMULA} or {@code TAG}; {@code null} keeps the stored
     *                    type and new sets default to {@code VALUE}
     */
    public record SetRequest(String code, String name, String description, Integer sortOrder, String type) {
    }

    /**
     * Meta item payload.
     *
     * @param code        item code
     * @param label       display label
     * @param refSetCode  set referenced by this item, used by value types
     * @param sortOrder   display order; {@code null} appends the item at the end
     * @param enabled     whether the item can be selected; {@code null} keeps the stored value and new
     *                    items are enabled by default
     * @param remark      free form note
     * @param content     value text of a VALUE or TAG set, the formula of a FORMULA set or the pattern
     *                    of a VALIDATION set
     * @param ruleType    {@code REGEX} or {@code WILDCARD} for the pattern of a VALIDATION set
     * @param severity    {@code WARN} or {@code ERROR} for a rule that rejects a value; {@code null}
     *                    keeps the stored level and new rules warn
     */
    public record ItemRequest(String code, String label, String refSetCode, Integer sortOrder,
            Boolean enabled, String remark, String content, String ruleType, String severity) {
    }

    /**
     * Rule test payload.
     *
     * @param expression rule codes combined with AND / OR / NOT and parentheses
     * @param text       sample value
     */
    public record RuleTestRequest(String expression, String text) {
    }

    /**
     * Request of the pattern test endpoint.
     *
     * @param ruleType   {@code REGEX} or {@code WILDCARD}
     * @param expression the pattern text
     * @param text       sample value
     */
    public record PatternMatchRequest(String ruleType, String expression, String text) {
    }
}
