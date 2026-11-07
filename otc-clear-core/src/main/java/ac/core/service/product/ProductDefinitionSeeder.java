package ac.core.service.product;

import ac.core.common.Ids;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.BaseProductRepository.BaseFieldRow;
import ac.core.persistence.BaseProductRepository.BaseProductRow;
import ac.core.persistence.MetaRepository;
import ac.core.persistence.ProductRepository;
import ac.core.persistence.ProductRepository.ProductFieldRow;
import ac.core.persistence.ProductRepository.ProductRow;
import ac.core.persistence.SettingRepository;
import ac.core.service.meta.MetaService;
import ac.domain.product.BaseProductCode;
import ac.domain.product.PlatformCode;
import ac.domain.product.ProductSchema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the platform catalogue, the field meta information and a starter product catalogue once per
 * tenant. The marker in {@code app_setting} prevents re-seeding after the user deletes entries.
 */
@Slf4j
@Component
public class ProductDefinitionSeeder implements ApplicationRunner {

    static final String MARKER_KEY = "product.seed.version";
    static final String MARKER_VALUE = "3";

    /** Role code of a business column; derived products keep them required. */
    static final String BUSINESS_ROLE = "BUSINESS";

    private static boolean isBusinessRole(String roleCode) {
        return BUSINESS_ROLE.equalsIgnoreCase(roleCode == null ? "" : roleCode);
    }

    private final MetaRepository metaRepository;
    private final BaseProductRepository baseRepository;
    private final ProductRepository productRepository;
    private final SettingRepository settings;
    private final ProductSchemaMapper schemaMapper;
    private final MetaLookup metaLookup;
    private final AppProperties properties;
    private final TimeProvider time;

    public ProductDefinitionSeeder(MetaRepository metaRepository, BaseProductRepository baseRepository,
            ProductRepository productRepository, SettingRepository settings, ProductSchemaMapper schemaMapper,
            MetaLookup metaLookup, AppProperties properties, TimeProvider time) {
        this.metaRepository = metaRepository;
        this.baseRepository = baseRepository;
        this.productRepository = productRepository;
        this.settings = settings;
        this.schemaMapper = schemaMapper;
        this.metaLookup = metaLookup;
        this.properties = properties;
        this.time = time;
    }

    /** One seeded field: key, header, value type, role, required, aliases, tags. */
    private record SeedField(String key, String header, String valueTypeCode, String roleCode,
            boolean required, List<String> aliases, List<String> tags, boolean keyFlag) {

        static SeedField text(String key, String header) {
            return new SeedField(key, header, "TEXT", "BUSINESS", false, List.of(), List.of(), false);
        }

        static SeedField text(String key, String header, List<String> aliases) {
            return new SeedField(key, header, "TEXT", "BUSINESS", false, aliases, List.of(), false);
        }

        static SeedField tagged(String key, String header, String tag) {
            return new SeedField(key, header, "TEXT", "BUSINESS", false, List.of(), List.of(tag), false);
        }

        static SeedField date(String key, String header, boolean required) {
            return new SeedField(key, header, "DATE", "BUSINESS", required, List.of(), List.of(), false);
        }

        static SeedField decimal(String key, String header, boolean required, List<String> aliases) {
            return new SeedField(key, header, "DECIMAL", "BUSINESS", required, aliases, List.of(), false);
        }

        static SeedField keyField() {
            return new SeedField("tradeId", "Trade ID", "TEXT", "BUSINESS", true,
                    List.of("TradeID", "Trade Ref"), List.of(), true);
        }

        /** Business column with an explicit value type. */
        static SeedField trade(String key, String header, String type, boolean required,
                String... aliases) {
            return new SeedField(key, header, type, "BUSINESS", required, List.of(aliases), List.of(), false);
        }

        /** Business column carrying a single tag. */
        static SeedField tradeTagged(String key, String header, String type, boolean required,
                String tag) {
            return new SeedField(key, header, type, "BUSINESS", required, List.of(), List.of(tag), false);
        }

        /** Managed column: declared by the product, kept outside the trade block and optional. */
        static SeedField managed(String key, String header) {
            return new SeedField(key, header, "TEXT", "MANAGED", false, List.of(), List.of(), false);
        }
    }

    private static final List<SeedField> IRS_FIELDS = List.of(
            SeedField.keyField(),
            SeedField.date("tradeDate", "Trade Date", true),
            SeedField.date("effectiveDate", "Effective Date", false),
            SeedField.date("maturityDate", "Maturity Date", true),
            SeedField.decimal("notional", "Notional", true, List.of()),
            SeedField.text("notionalCurrency", "Notional Currency", List.of("CCY")),
            SeedField.decimal("fixedRate", "Rate", false, List.of()),
            SeedField.decimal("floatingRate", "Rate", false, List.of("Floating Rate")),
            SeedField.text("fixedFrequency", "Fixed Frequency"),
            SeedField.text("floatingFrequency", "Floating Frequency"),
            SeedField.text("dayCount", "Day Count", List.of("Day Count Convention")),
            SeedField.text("businessDayConvention", "BDC", List.of("Business Day Convention")),
            SeedField.text("paymentCalendar", "Calendar", List.of("Payment Calendar")),
            SeedField.tagged("clearingHouse", "Clearing House", "CLEARING"),
            SeedField.tagged("clearingMember", "Clearing Member", "CLEARING"),
            SeedField.text("account", "Account"),
            SeedField.text("counterparty", "Counterparty"),
            SeedField.text("direction", "Direction", List.of("Trade Direction")),
            SeedField.tagged("upi", "UPI", "REPORT"),
            SeedField.tagged("reportingParty", "Reporting Party", "REPORT"),
            SeedField.text("tradeState", "Trade State", List.of("Status")),
            SeedField.date("novationDate", "Novation Date", false),
            SeedField.text("portfolio", "Portfolio"),
            new SeedField("usPerson", "US Person", "BOOLEAN", "BUSINESS", false, List.of(), List.of(), false));

    private static final List<SeedField> OIS_FIELDS = List.of(
            SeedField.keyField(),
            SeedField.date("tradeDate", "Trade Date", true),
            SeedField.date("effectiveDate", "Effective Date", false),
            SeedField.date("maturityDate", "Maturity Date", true),
            SeedField.decimal("notional", "Notional", true, List.of()),
            SeedField.text("notionalCurrency", "Notional Currency", List.of("CCY")),
            SeedField.decimal("overnightRate", "Rate", false, List.of("Overnight Rate")),
            SeedField.text("compoundingMethod", "Compounding", List.of("Compounding Method")),
            SeedField.text("paymentFrequency", "Payment Frequency"),
            SeedField.text("dayCount", "Day Count"),
            SeedField.text("businessDayConvention", "BDC", List.of("Business Day Convention")),
            SeedField.text("paymentCalendar", "Calendar", List.of("Payment Calendar")),
            SeedField.tagged("clearingHouse", "Clearing House", "CLEARING"),
            SeedField.tagged("clearingMember", "Clearing Member", "CLEARING"),
            SeedField.text("account", "Account"),
            SeedField.text("counterparty", "Counterparty"),
            SeedField.text("direction", "Direction"),
            SeedField.tagged("upi", "UPI", "REPORT"),
            SeedField.text("tradeState", "Trade State", List.of("Status")),
            SeedField.text("portfolio", "Portfolio"));

    /**
     * MW/CCSIRS ships a complete 70 column template: 4 managed columns plus 66 business columns,
     * of which the first 50 are required.
     */
    private static final List<SeedField> CCSIRS_FIELDS = List.of(
            SeedField.managed("testCaseId", "_TestCaseId"),
            SeedField.managed("remarks", "_Remarks"),
            SeedField.managed("scenarioGroup", "Scenario Group"),
            SeedField.managed("priority", "Priority"),
            SeedField.keyField(),
            SeedField.trade("tradeDate", "Trade Date", "DATE", true),
            SeedField.trade("effectiveDate", "Effective Date", "DATE", true),
            SeedField.trade("maturityDate", "Maturity Date", "DATE", true),
            SeedField.trade("notional", "Notional", "DECIMAL", true),
            SeedField.trade("notionalCurrency", "Notional Currency", "TEXT", true, "CCY"),
            SeedField.trade("notional2", "Notional 2", "DECIMAL", true, "Second Notional"),
            SeedField.trade("notionalCurrency2", "Notional Currency 2", "TEXT", true, "CCY 2"),
            SeedField.trade("direction", "Direction", "TEXT", true, "Trade Direction"),
            SeedField.trade("fixedRate", "Fixed Rate", "DECIMAL", true, "Rate"),
            SeedField.trade("floatingRate", "Floating Rate", "DECIMAL", true, "Rate"),
            SeedField.trade("basisSpread", "Basis Spread", "DECIMAL", true),
            SeedField.trade("fixedFrequency", "Fixed Frequency", "TEXT", true),
            SeedField.trade("floatingFrequency", "Floating Frequency", "TEXT", true),
            SeedField.trade("fixedDayCount", "Fixed Day Count", "TEXT", true, "Day Count"),
            SeedField.trade("floatingDayCount", "Floating Day Count", "TEXT", true),
            SeedField.trade("paymentCalendar", "Payment Calendar", "TEXT", true, "Calendar"),
            SeedField.trade("resetCalendar", "Reset Calendar", "TEXT", true),
            SeedField.trade("paymentBdc", "Payment BDC", "TEXT", true, "BDC", "Business Day Convention"),
            SeedField.trade("resetBdc", "Reset BDC", "TEXT", true),
            SeedField.trade("tradeState", "Trade State", "TEXT", true, "Status"),
            SeedField.tradeTagged("clearingHouse", "Clearing House", "TEXT", true, "CLEARING"),
            SeedField.tradeTagged("clearingMember", "Clearing Member", "TEXT", true, "CLEARING"),
            SeedField.tradeTagged("account", "Account", "TEXT", true, "CLEARING"),
            SeedField.trade("counterparty", "Counterparty", "TEXT", true),
            SeedField.trade("counterpartyLei", "Counterparty LEI", "TEXT", true),
            SeedField.tradeTagged("reportingParty", "Reporting Party", "TEXT", true, "REPORT"),
            SeedField.tradeTagged("upi", "UPI", "TEXT", true, "REPORT"),
            SeedField.tradeTagged("uti", "UTI", "TEXT", true, "REPORT"),
            SeedField.trade("portfolio", "Portfolio", "TEXT", true),
            SeedField.trade("tradeType", "Trade Type", "TEXT", true),
            SeedField.trade("product", "Product", "TEXT", true),
            SeedField.trade("swapSubType", "Swap Sub Type", "TEXT", true),
            SeedField.trade("settlementCurrency", "Settlement Currency", "TEXT", true),
            SeedField.trade("settlementDate", "Settlement Date", "DATE", true),
            SeedField.trade("fixedLegPayer", "Fixed Leg Payer", "TEXT", true),
            SeedField.trade("floatingLegPayer", "Floating Leg Payer", "TEXT", true),
            SeedField.trade("fixedLegNotional", "Fixed Leg Notional", "DECIMAL", true),
            SeedField.trade("floatingLegNotional", "Floating Leg Notional", "DECIMAL", true),
            SeedField.trade("initialExchangeRate", "Initial Exchange Rate", "DECIMAL", true),
            SeedField.trade("finalExchangeRate", "Final Exchange Rate", "DECIMAL", true),
            SeedField.trade("exchangeRateSource", "Exchange Rate Source", "TEXT", true),
            SeedField.tradeTagged("valuationDate", "Valuation Date", "DATE", true, "CHECK"),
            SeedField.trade("collateralType", "Collateral Type", "TEXT", true),
            SeedField.trade("marginAgreement", "Margin Agreement", "TEXT", true),
            SeedField.trade("bookingSystem", "Booking System", "TEXT", true),
            SeedField.trade("executionVenue", "Execution Venue", "TEXT", true),
            SeedField.tradeTagged("confirmationMethod", "Confirmation Method", "TEXT", true, "CHECK"),
            SeedField.trade("reportable", "Reportable", "BOOLEAN", true),
            SeedField.trade("usPerson", "US Person", "BOOLEAN", true),
            SeedField.trade("terminationDate", "Termination Date", "DATE", false),
            SeedField.trade("novationDate", "Novation Date", "DATE", false),
            SeedField.trade("compressionDate", "Compression Date", "DATE", false),
            SeedField.trade("amendmentDate", "Amendment Date", "DATE", false),
            SeedField.trade("platform", "Platform", "TEXT", false),
            SeedField.trade("broker", "Broker", "TEXT", false),
            SeedField.trade("trader", "Trader", "TEXT", false),
            SeedField.trade("salesPerson", "Sales Person", "TEXT", false),
            SeedField.trade("strategy", "Strategy", "TEXT", false),
            SeedField.tradeTagged("hedgeType", "Hedge Type", "TEXT", false, "RISK"),
            SeedField.tradeTagged("riskClass", "Risk Class", "TEXT", false, "RISK"),
            SeedField.trade("comment", "Comment", "TEXT", false),
            SeedField.trade("externalReference", "External Reference", "TEXT", false),
            SeedField.trade("settlementMethod", "Settlement Method", "TEXT", false),
            SeedField.trade("nettingSet", "Netting Set", "TEXT", false),
            SeedField.trade("regulatoryRegime", "Regulatory Regime", "TEXT", false));
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String tenantId = properties.getTenant().getId();
        Optional<String> marker = settings.find(tenantId, MARKER_KEY);
        if (marker.isEmpty()) {
            seedPlatforms();
            seedMeta(tenantId);
            seedCatalogue(tenantId);
            settings.put(tenantId, MARKER_KEY, MARKER_VALUE, time.nowUtc());
            log.info("Seeded product definitions for tenant {}", tenantId);
            return;
        }
        if (MARKER_VALUE.equals(marker.get())) {
            return;
        }
        upgradeToCurrentVersion(tenantId, marker.get());
        settings.put(tenantId, MARKER_KEY, MARKER_VALUE, time.nowUtc());
    }

    /**
     * Brings an existing catalogue up to the current seed version without touching user data: the
     * field roles are collapsed to business/managed and the MW/CCSIRS template is replaced when the
     * catalogue still carries the v1 definition.
     */
    private void upgradeToCurrentVersion(String tenantId, String fromVersion) {
        ensureRoleItems(tenantId);
        if ("1".equals(fromVersion)) {
            replaceCcsirsFields(tenantId);
        }
        log.info("Upgraded product definitions of tenant {} from seed version {}", tenantId, fromVersion);
    }

    /** Collapses TRADE/KEY into BUSINESS and keeps MANAGED, matching the current role set. */
    private void ensureRoleItems(String tenantId) {
        for (String obsolete : List.of("TRADE", "KEY")) {
            if (metaRepository.item(tenantId, MetaService.FIELD_ROLE_SET, obsolete).isPresent()) {
                metaRepository.deleteItem(tenantId, MetaService.FIELD_ROLE_SET, obsolete);
            }
        }
        upsertRoleItem(tenantId, BUSINESS_ROLE, "业务字段", 1);
        upsertRoleItem(tenantId, "MANAGED", "管理字段", 2);
    }

    private void upsertRoleItem(String tenantId, String code, String label, int sortOrder) {
        if (metaRepository.item(tenantId, MetaService.FIELD_ROLE_SET, code).isPresent()) {
            metaRepository.updateItem(tenantId, MetaService.FIELD_ROLE_SET, code, label, null, sortOrder,
                    true, null, time.nowUtc());
            return;
        }
        metaRepository.insertItem(tenantId, new MetaRepository.MetaItemRow(MetaService.FIELD_ROLE_SET, code,
                label, null, sortOrder, true, null), time.nowUtc());
    }

    private void replaceCcsirsFields(String tenantId) {
        baseRepository.findByCode(tenantId, PlatformCode.MW.name(), BaseProductCode.CCSIRS.name())
                .ifPresent(base -> {
                    List<BaseFieldRow> existing = baseRepository.listFields(tenantId, base.id());
                    if (existing.size() == CCSIRS_FIELDS.size()) {
                        return;
                    }
                    for (BaseFieldRow field : existing) {
                        baseRepository.deleteField(field.id());
                    }
                    writeBaseFields(tenantId, base.id(), CCSIRS_FIELDS);
                    ProductSchema schema = schemaMapper.toBaseSchema(base,
                            baseRepository.listFields(tenantId, base.id()), metaLookup.load(tenantId));
                    baseRepository.updateSchemaHash(base.id(), schema.schemaHash(), time.nowUtc());
                    for (ProductRow product : productRepository.listByBaseProduct(tenantId, base.id())) {
                        for (ProductFieldRow field : productRepository.listFields(tenantId, product.id())) {
                            productRepository.deleteField(field.id());
                        }
                        copyBaseFields(tenantId, product.id(), baseRepository.listFields(tenantId, base.id()));
                        ProductSchema productSchema = schemaMapper.toSchema(product,
                                productRepository.listFields(tenantId, product.id()),
                                metaLookup.load(tenantId));
                        productRepository.updateSchemaHash(product.id(), productSchema.schemaHash(),
                                time.nowUtc());
                    }
                    log.info("Replaced MW/CCSIRS with {} predefined fields", CCSIRS_FIELDS.size());
                });
    }

    private void writeBaseFields(String tenantId, String baseProductId, List<SeedField> fields) {
        LocalDateTime now = time.nowUtc();
        int order = 0;
        for (SeedField field : fields) {
            order++;
            baseRepository.insertField(new BaseFieldRow(Ids.newId(), tenantId, baseProductId, field.key(),
                    field.header(), field.header(), field.aliases(), field.valueTypeCode(), field.roleCode(),
                    field.tags(), field.required(), true, field.keyFlag(), order, null, "", ""));
        }
        log.debug("Wrote {} fields for base product {}", order, baseProductId);
    }

    private void copyBaseFields(String tenantId, String productId, List<BaseFieldRow> baseFields) {
        LocalDateTime now = time.nowUtc();
        int order = 0;
        for (BaseFieldRow field : baseFields) {
            order++;
            productRepository.insertField(new ProductFieldRow(Ids.newId(), tenantId, productId, field.id(),
                    field.fieldKey(), field.header(), field.displayName(), field.aliases(),
                    field.valueTypeCode(), field.roleCode(), field.tags(),
                    isBusinessRole(field.roleCode()) || field.required(),
                    field.businessKey(), field.keyFlag(), order, field.remark(), field.formatHint(),
                    field.clonePattern()), now);
        }
    }

    private void seedPlatforms() {
        metaRepository.upsertPlatform(new MetaRepository.PlatformRow(PlatformCode.MW.name(),
                PlatformCode.MW.displayName(), "MarkitWire platform", 1, true));
        metaRepository.upsertPlatform(new MetaRepository.PlatformRow(PlatformCode.CFETS.name(),
                PlatformCode.CFETS.displayName(), "CFETS platform", 2, true));
    }

    private void seedMeta(String tenantId) {
        LocalDateTime now = time.nowUtc();
        seedSet(tenantId, new MetaRepository.MetaSetRow("VALUE_TYPE", "值类型", "字段取值的类型", true, 1), now,
                List.of(
                        item("TEXT", "文本", null),
                        item("INTEGER", "整数", null),
                        item("DECIMAL", "小数", null),
                        item("DATE", "日期", null),
                        item("BOOLEAN", "布尔", null),
                        item("CONV_METHOD", "Conversion Method（枚举引用）", "CONV_METHOD")));
        seedSet(tenantId, new MetaRepository.MetaSetRow("FIELD_ROLE", "角色类型", "字段在交易块中的角色", true, 2),
                now, List.of(item("BUSINESS", "业务字段", null), item("MANAGED", "管理字段", null)));
        seedSet(tenantId, new MetaRepository.MetaSetRow("FIELD_TAG", "字段标签", "用于分类与检索的标签", true, 3),
                now, List.of(
                        item("CLEARING", "清算", null),
                        item("REPORT", "报文", null),
                        item("RISK", "风险", null),
                        item("CHECK", "核对", null)));
        seedSet(tenantId, new MetaRepository.MetaSetRow("CONV_METHOD", "Conversion Method",
                "业务日调整方式示例枚举", true, 4), now, List.of(
                item("MODFOLLOWING", "Modified Following", null),
                item("FLOWING", "Flowing", null),
                item("PRECEDING", "Preceding", null)));
    }

    private MetaRepository.MetaItemRow item(String code, String label, String refSet) {
        return new MetaRepository.MetaItemRow(null, code, label, refSet, 0, true, null);
    }

    private void seedSet(String tenantId, MetaRepository.MetaSetRow set, LocalDateTime now,
            List<MetaRepository.MetaItemRow> items) {
        metaRepository.insertSet(tenantId, set, now);
        int order = 0;
        for (MetaRepository.MetaItemRow item : items) {
            order++;
            metaRepository.insertItem(tenantId, new MetaRepository.MetaItemRow(set.code(), item.code(),
                    item.label(), item.refSetCode(), order, true, item.remark()), now);
        }
    }

    private void seedCatalogue(String tenantId) {
        List<BaseProductRow> mwBases = new ArrayList<>();
        int order = 0;
        for (BaseProductCode code : BaseProductCode.all()) {
            order++;
            List<SeedField> fields = switch (code) {
                case IRS -> IRS_FIELDS;
                case OIS -> OIS_FIELDS;
                case CCSIRS -> CCSIRS_FIELDS;
                default -> List.of();
            };
            mwBases.add(insertBase(tenantId, PlatformCode.MW.name(), code, order, fields));
        }

        insertBase(tenantId, PlatformCode.CFETS.name(), BaseProductCode.IRS, 1, List.of());
        insertBase(tenantId, PlatformCode.CFETS.name(), BaseProductCode.NDS, 2, List.of());
        insertBase(tenantId, PlatformCode.CFETS.name(), BaseProductCode.CNYNDS, 3, List.of());

        for (BaseProductRow base : mwBases) {
            switch (base.code()) {
                case "IRS" -> {
                    insertProduct(tenantId, base, "IRS", "IRS Standard", "Full IRS field set", Set.of());
                    insertProduct(tenantId, base, "IRS_SIMPLE", "IRS Simple",
                            "Derived from IRS, drops reporting and portfolio fields",
                            Set.of("upi", "reportingParty", "portfolio", "usPerson", "novationDate",
                                    "counterparty"));
                }
                case "OIS" -> insertProduct(tenantId, base, "OIS", "OIS Standard", "Full OIS field set", Set.of());
                case "CCSIRS" -> insertProduct(tenantId, base, "CCSIRS", "CCSIRS Standard",
                        "Full CCSIRS field set", Set.of());
                default -> {
                    // no starter product for the remaining base products
                }
            }
        }
    }

    private BaseProductRow insertBase(String tenantId, String platformCode, BaseProductCode code, int sortOrder,
            List<SeedField> fields) {
        LocalDateTime now = time.nowUtc();
        BaseProductRow row = new BaseProductRow(Ids.newId(), tenantId, platformCode, code.name(),
                code.displayName(), null, "ACTIVE", sortOrder, null, now, now);
        baseRepository.insert(row);
        int order = 0;
        for (SeedField field : fields) {
            order++;
            baseRepository.insertField(new BaseFieldRow(Ids.newId(), tenantId, row.id(), field.key(),
                    field.header(), field.header(), field.aliases(), field.valueTypeCode(), field.roleCode(),
                    field.tags(), field.required(), true, field.keyFlag(), order, null, "", ""));
        }
        ProductSchema schema = schemaMapper.toBaseSchema(row,
                baseRepository.listFields(tenantId, row.id()), metaLookup.load(tenantId));
        baseRepository.updateSchemaHash(row.id(), schema.schemaHash(), now);
        return baseRepository.findById(tenantId, row.id()).orElseThrow();
    }

    private void insertProduct(String tenantId, BaseProductRow base, String code, String name,
            String description, Set<String> excludedFieldKeys) {
        LocalDateTime now = time.nowUtc();
        Set<String> excluded = new LinkedHashSet<>(excludedFieldKeys);
        ProductRow product = new ProductRow(Ids.newId(), tenantId, base.platformCode(), base.id(), base.code(),
                code, name, description, "ACTIVE", false, null, now, now);
        productRepository.insert(product);
        for (BaseFieldRow field : baseRepository.listFields(tenantId, base.id())) {
            if (excluded.contains(field.fieldKey())) {
                continue;
            }
            productRepository.insertField(new ProductFieldRow(Ids.newId(), tenantId, product.id(), field.id(),
                    field.fieldKey(), field.header(), field.displayName(), field.aliases(),
                    field.valueTypeCode(), field.roleCode(), field.tags(),
                    isBusinessRole(field.roleCode()) || field.required(),
                    field.businessKey(), field.keyFlag(), field.sortOrder(), field.remark(),
                    field.formatHint(), field.clonePattern()), now);
        }
        ProductSchema schema = schemaMapper.toSchema(product,
                productRepository.listFields(tenantId, product.id()), metaLookup.load(tenantId));
        productRepository.updateSchemaHash(product.id(), schema.schemaHash(), now);
    }
}
