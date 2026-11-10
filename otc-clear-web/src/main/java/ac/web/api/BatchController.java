package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.persistence.BatchFilter;
import ac.core.persistence.CaseFilter;
import ac.core.service.batch.BatchService;
import ac.web.config.TenantProvider;
import ac.web.dto.ViewMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Batch endpoints. */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;
    private final TenantProvider tenantProvider;

    public BatchController(BatchService batchService, TenantProvider tenantProvider) {
        this.batchService = batchService;
        this.tenantProvider = tenantProvider;
    }

    /**
     * Console list: filters by product, kind, status, tag, favorite, pinned and creation date, sorted
     * by {@code created|name|rows|invalid|product} with an optional {@code _asc}/{@code _desc} suffix.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String product,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) Boolean pinned,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false, defaultValue = "false") boolean includeExternal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        BatchFilter filter = new BatchFilter(product, query, kind, status, tag, favorite, pinned,
                parseDate(from), parseDate(to), sort, includeDeleted, includeExternal, pageQuery);
        var result = batchService.search(tenantProvider.tenantId(), filter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(ViewMapper::batch).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }

    /** Counter chips of the console; accepts the same filters as the list. */
    @GetMapping("/facets")
    public BatchService.BatchFacets facets(@RequestParam(required = false) String product,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) Boolean pinned,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false, defaultValue = "false") boolean includeExternal) {
        BatchFilter filter = new BatchFilter(product, query, kind, status, tag, favorite, pinned,
                parseDate(from), parseDate(to), "created", includeDeleted, includeExternal,
                PageQuery.of(1, 1));
        return batchService.facets(tenantProvider.tenantId(), filter);
    }

    /** Recent and pinned batches of the dock. */
    @GetMapping("/dock")
    public List<Map<String, Object>> dock(@RequestParam(required = false, defaultValue = "12") int limit,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted) {
        return batchService.dock(tenantProvider.tenantId(), limit, includeDeleted).stream()
                .map(ViewMapper::batch).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        var batch = batchService.require(tenantProvider.tenantId(), id);
        Map<String, Object> body = new LinkedHashMap<>(ViewMapper.batch(batch));
        body.put("activeCases", batchService.activeCaseCount(tenantProvider.tenantId(), id));
        return body;
    }

    @PatchMapping("/{id}")
    public Map<String, Object> updateNote(@PathVariable String id, @RequestBody Map<String, String> body) {
        batchService.updateNote(tenantProvider.tenantId(), id, body.get("note"));
        return Map.of("updated", true);
    }

    /**
     * Updates the console metadata; omitted fields keep their stored value.
     *
     * @param body {@code favorite}, {@code pinned}, {@code tags} (list or comma separated) and {@code note}
     */
    @PatchMapping("/{id}/meta")
    public Map<String, Object> updateMeta(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Boolean favorite = booleanValue(body.get("favorite"));
        Boolean pinned = booleanValue(body.get("pinned"));
        List<String> tags = body.containsKey("tags") ? stringList(body.get("tags")) : null;
        String note = body.containsKey("note") ? String.valueOf(body.get("note")) : null;
        var row = batchService.updateMeta(tenantProvider.tenantId(), id, favorite, pinned, tags, note);
        return new LinkedHashMap<>(ViewMapper.batch(row));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        batchService.delete(tenantProvider.tenantId(), id);
        return Map.of("deleted", true);
    }

    @GetMapping("/{id}/cases")
    public Map<String, Object> cases(@PathVariable String id,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Boolean hasFormula,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "true") boolean withSummary,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        String tenantId = tenantProvider.tenantId();
        var result = batchService.cases(tenantId, id, new CaseFilter(id, query, hasFormula, sort, pageQuery));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        if (withSummary) {
            body.put("summary", batchService.caseSummary(tenantId, id, query, hasFormula));
        }
        return body;
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.valueOf(text.trim());
        }
        return null;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(item -> item != null).map(String::valueOf).toList();
        }
        if (value instanceof String text) {
            return text.isBlank() ? List.of() : java.util.Arrays.stream(text.split(","))
                    .map(String::trim).filter(tag -> !tag.isEmpty()).toList();
        }
        return List.of();
    }

    /** Parses an ISO date from the query string; a blank or malformed value means "no bound". */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ac.core.common.ApiException.invalid("Invalid date '" + value + "', expected yyyy-MM-dd");
        }
    }
}
