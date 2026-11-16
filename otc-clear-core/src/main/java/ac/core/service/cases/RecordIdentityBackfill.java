package ac.core.service.cases;

import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.SettingRepository;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.product.ProductCatalog;
import ac.domain.casefile.CellTexts;
import ac.domain.casefile.Formulas;
import ac.domain.casefile.RecordIdGenerator;
import ac.domain.product.ProductSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Keeps the stored record ids in line with the hashing rules.
 *
 * <p>The ids cannot be derived in SQL because they depend on the product definition, so they are
 * recomputed at startup: once per tenant when the hashing rules changed ({@link #VERSION}), and for
 * any row whose core id is still missing. Rows whose product no longer exists are left untouched and
 * the loop always terminates.
 */
@Component
public class RecordIdentityBackfill implements ApplicationRunner {

    /**
     * Version of the hashing rules, stored per tenant so a rule change triggers one recomputation.
     *
     * <p>{@code raw-text-v2}: values enter the hash as the original cell text read at import time
     * (or the text the user typed), managed columns take part in the business record id.
     */
    static final String VERSION = "raw-text-v2";

    private static final String SETTING_KEY = "identity.recordIdsVersion";
    private static final Logger log = LoggerFactory.getLogger(RecordIdentityBackfill.class);
    private static final int CHUNK = 500;

    private final TestCaseRepository caseRepository;
    private final SettingRepository settings;
    private final ProductCatalog products;
    private final CaseJsonCodec codec;
    private final JsonSupport json;
    private final AppProperties properties;
    private final TimeProvider time;

    public RecordIdentityBackfill(TestCaseRepository caseRepository, SettingRepository settings,
            ProductCatalog products, CaseJsonCodec codec, JsonSupport json, AppProperties properties,
            TimeProvider time) {
        this.caseRepository = caseRepository;
        this.settings = settings;
        this.products = products;
        this.codec = codec;
        this.json = json;
        this.properties = properties;
        this.time = time;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int updated = backfill();
            if (updated > 0) {
                log.info("Recomputed the record ids of {} case rows", updated);
            }
        } catch (RuntimeException e) {
            // never block the startup: the ids only feed a table column and a tooltip
            log.warn("Record id backfill failed: {}", e.getMessage());
        }
    }

    /** Returns the number of rows updated. */
    int backfill() {
        int total = repairMissingCoreIds();
        for (String tenantId : caseRepository.listTenantIds()) {
            if (VERSION.equals(settings.find(tenantId, SETTING_KEY).orElse(""))) {
                continue;
            }
            total += recomputeTenant(tenantId);
            settings.put(tenantId, SETTING_KEY, VERSION, time.nowUtc());
        }
        return total;
    }

    /** Full recomputation of one tenant, walking the rows by id so memory stays flat. */
    private int recomputeTenant(String tenantId) {
        int updated = 0;
        String afterId = "";
        while (true) {
            List<TestCaseRecord> chunk = caseRepository.pageForIdentity(tenantId, afterId, CHUNK);
            if (chunk.isEmpty()) {
                return updated;
            }
            for (TestCaseRecord row : chunk) {
                afterId = row.id();
                updated += refresh(row);
            }
        }
    }

    /** Rows imported before the core id existed, regardless of the rules version. */
    private int repairMissingCoreIds() {
        int updated = 0;
        java.util.Set<String> attempted = new java.util.HashSet<>();
        while (true) {
            List<TestCaseRecord> chunk = caseRepository.listMissingCoreBusinessId(CHUNK).stream()
                    .filter(row -> !attempted.contains(row.id()))
                    .toList();
            if (chunk.isEmpty()) {
                return updated;
            }
            int changed = 0;
            for (TestCaseRecord row : chunk) {
                attempted.add(row.id());
                changed += refresh(row);
            }
            updated += changed;
            if (changed == 0) {
                return updated;
            }
        }
    }

    /** Rewrites both ids of one row when they differ; returns 1 when the row was touched. */
    private int refresh(TestCaseRecord row) {
        Optional<ProductSchema> schema = products.find(row.tenantId(), row.productCode());
        if (schema.isEmpty()) {
            return 0;
        }
        CellTexts texts = codec.texts(row);
        Map<String, String> formulas = hashFormulas(codec.tradeFormulas(row.formulaJson()));
        String businessId = RecordIdGenerator.businessId(schema.get(), texts, formulas,
                properties.getIds().getHashLength());
        String coreId = RecordIdGenerator.coreBusinessId(schema.get(), texts, formulas,
                properties.getIds().getHashLength());
        boolean businessMissing = businessId == null || businessId.isBlank();
        boolean coreMissing = coreId == null || coreId.isBlank();
        if (businessMissing && coreMissing) {
            return 0;
        }
        String resolvedBusinessId = businessMissing ? row.businessRecordId() : businessId;
        String resolvedCoreId = coreMissing ? row.originalRecordId() : coreId;
        if (resolvedBusinessId == null || resolvedCoreId == null) {
            return 0;
        }
        if (resolvedBusinessId.equals(row.businessRecordId())
                && resolvedCoreId.equals(row.coreBusinessRecordId())) {
            return 0;
        }
        return caseRepository.updateBusinessIds(row.tenantId(), row.id(), resolvedBusinessId, resolvedCoreId);
    }

    private Map<String, String> hashFormulas(Map<String, String> formulas) {
        return properties.getIds().isNormalizeFormulaRefs() ? Formulas.normalized(formulas) : formulas;
    }
}
