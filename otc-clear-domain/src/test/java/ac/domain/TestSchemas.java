package ac.domain;

import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import ac.domain.product.ProductSchema;
import java.util.List;

/** Schema fixtures shared by domain tests; mirrors the seeded IRS/OIS/CCSIRS field sets. */
public final class TestSchemas {

    private TestSchemas() {
    }

    private static FieldDef f(String key, int order, String header, FieldType type, FieldRole role,
            boolean required, boolean businessKey, List<String> aliases) {
        return FieldDef.of(key, order, header, aliases, type, role, required, businessKey, "", "", 1);
    }

    /** Business key column; {@code keyFlag} marks the single trade id of the schema. */
    private static FieldDef keyField(String key, int order, String header, boolean required,
            List<String> aliases) {
        return new FieldDef(key, order, header, aliases, FieldType.TEXT, FieldRole.BUSINESS, required, true,
                true, "", "", 1, List.of(), List.of());
    }

    private static FieldDef text(String key, int order, String header) {
        return f(key, order, header, FieldType.TEXT, FieldRole.BUSINESS, false, true, List.of());
    }

    /** Managed column outside the trade block, e.g. {@code _TestCaseId}. */
    private static FieldDef managed(String key, int order, String header) {
        return f(key, order, header, FieldType.TEXT, FieldRole.MANAGED, false, false, List.of());
    }

    public static ProductSchema irs() {
        return ProductSchema.assemble("IRS", "Interest Rate Swap", "", List.of(
                managed("testCaseId", 1, "_TestCaseId"),
                managed("remarks", 2, "_Remarks"),
                keyField("tradeId", 3, "Trade ID", true,
                        List.of("TradeID", "Trade Ref")),
                f("tradeDate", 4, "Trade Date", FieldType.DATE, FieldRole.BUSINESS, true, true, List.of()),
                f("effectiveDate", 5, "Effective Date", FieldType.DATE, FieldRole.BUSINESS, false, true, List.of()),
                f("maturityDate", 6, "Maturity Date", FieldType.DATE, FieldRole.BUSINESS, true, true, List.of()),
                f("notional", 7, "Notional", FieldType.DECIMAL, FieldRole.BUSINESS, true, true, List.of()),
                f("notionalCurrency", 8, "Notional Currency", FieldType.TEXT, FieldRole.BUSINESS, true, true,
                        List.of("CCY")),
                f("fixedRate", 9, "Rate", FieldType.DECIMAL, FieldRole.BUSINESS, false, true, List.of()),
                f("floatingRate", 10, "Rate", FieldType.DECIMAL, FieldRole.BUSINESS, false, true,
                        List.of("Floating Rate")),
                text("fixedFrequency", 11, "Fixed Frequency"),
                text("floatingFrequency", 12, "Floating Frequency"),
                text("dayCount", 13, "Day Count"),
                text("businessDayConvention", 14, "BDC"),
                text("paymentCalendar", 15, "Calendar"),
                text("clearingHouse", 16, "Clearing House"),
                text("clearingMember", 17, "Clearing Member"),
                text("account", 18, "Account"),
                text("counterparty", 19, "Counterparty"),
                text("direction", 20, "Direction"),
                text("upi", 21, "UPI"),
                text("reportingParty", 22, "Reporting Party"),
                text("tradeState", 23, "Trade State"),
                f("novationDate", 24, "Novation Date", FieldType.DATE, FieldRole.BUSINESS, false, true, List.of()),
                f("portfolio", 25, "Portfolio", FieldType.TEXT, FieldRole.BUSINESS, false, false, List.of()),
                f("usPerson", 26, "US Person", FieldType.BOOLEAN, FieldRole.BUSINESS, false, false, List.of())));
    }

    /** Minimal schema without managed columns; every non matching column is unmapped. */
    public static ProductSchema small() {
        return ProductSchema.assemble("SMALL", "Small", "", List.of(
                text("a", 1, "A"), text("b", 2, "B"), text("c", 3, "C")));
    }

    /** Minimal schema declaring the two system managed columns. */
    public static ProductSchema smallWithManaged() {
        return ProductSchema.assemble("SMALL", "Small", "", List.of(
                managed("testCaseId", 1, "_TestCaseId"),
                managed("remarks", 2, "_Remarks"),
                text("a", 3, "A"), text("b", 4, "B"), text("c", 5, "C")));
    }

    public static ProductSchema duplicateRate() {
        return ProductSchema.assemble("DUP", "Duplicate rate", "", List.of(
                keyField("tradeId", 1, "Trade ID", false, List.of()),
                text("fixedRate", 2, "Rate"),
                text("floatingRate", 3, "Rate")));
    }

    /** Same duplicated column names, but strict mode can tell them apart by position. */
    public static ProductSchema duplicateRateStrict() {
        return ProductSchema.assemble("DUP", "Duplicate rate", "", true, List.of(
                keyField("tradeId", 1, "Trade ID", false, List.of()),
                text("fixedRate", 2, "Rate"),
                text("floatingRate", 3, "Rate")));
    }

    /** Strict schema with the two system managed columns plus duplicated rate columns. */
    public static ProductSchema strictIrs() {
        return ProductSchema.assemble("IRS", "Interest Rate Swap", "", true, List.of(
                managed("testCaseId", 1, "_TestCaseId"),
                managed("remarks", 2, "_Remarks"),
                keyField("tradeId", 3, "Trade ID", false, List.of()),
                text("fixedRate", 4, "Rate"),
                text("floatingRate", 5, "Rate")));
    }

    public static ProductSchema analyzed() {
        return ProductSchema.assemble("AN", "Analyzed", "", List.of(
                keyField("tradeId", 1, "Trade ID", true, List.of()),
                f("tradeDate", 2, "Trade Date", FieldType.DATE, FieldRole.BUSINESS, true, true, List.of()),
                f("notional", 3, "Notional", FieldType.DECIMAL, FieldRole.BUSINESS, true, true, List.of())));
    }

    /** Same fields as {@link #analyzed()} but with the first two fields swapped by the user. */
    public static ProductSchema analyzedSwappedKeyOrder() {
        return ProductSchema.assemble("AN", "Analyzed", "", List.of(
                f("tradeDate", 1, "Trade Date", FieldType.DATE, FieldRole.BUSINESS, true, true, List.of()),
                keyField("tradeId", 2, "Trade ID", true, List.of()),
                f("notional", 3, "Notional", FieldType.DECIMAL, FieldRole.BUSINESS, true, true, List.of())));
    }
}
