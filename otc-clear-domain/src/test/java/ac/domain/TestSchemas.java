package ac.domain;

import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import ac.domain.product.ProductSchema;
import java.util.List;
import java.util.Set;

/** Schema fixtures shared by domain tests; mirrors the seeded IRS/OIS/CCSIRS field sets. */
public final class TestSchemas {

    private TestSchemas() {
    }

    private static FieldDef f(String key, int order, String header, FieldType type, FieldRole role,
            boolean required, boolean businessKey, List<String> aliases) {
        return FieldDef.of(key, order, header, aliases, type, role, required, businessKey, "", "", 1, Set.of());
    }

    /** Business key column; {@code keyFlag} marks the single trade id of the schema. */
    private static FieldDef keyField(String key, int order, String header, boolean required,
            List<String> aliases) {
        return new FieldDef(key, order, header, aliases, FieldType.TEXT, FieldRole.BUSINESS, required, true,
                true, "", "", 1, Set.of(), List.of(), List.of());
    }

    private static FieldDef text(String key, int order, String header) {
        return f(key, order, header, FieldType.TEXT, FieldRole.BUSINESS, false, true, List.of());
    }

    public static ProductSchema irs() {
        return ProductSchema.assemble("IRS", "Interest Rate Swap", "", List.of(
                keyField("tradeId", 1, "Trade ID", true,
                        List.of("TradeID", "Trade Ref")),
                f("tradeDate", 2, "Trade Date", FieldType.DATE, FieldRole.BUSINESS, true, true, List.of()),
                f("effectiveDate", 3, "Effective Date", FieldType.DATE, FieldRole.BUSINESS, false, true, List.of()),
                f("maturityDate", 4, "Maturity Date", FieldType.DATE, FieldRole.BUSINESS, true, true, List.of()),
                f("notional", 5, "Notional", FieldType.DECIMAL, FieldRole.BUSINESS, true, true, List.of()),
                f("notionalCurrency", 6, "Notional Currency", FieldType.TEXT, FieldRole.BUSINESS, true, true,
                        List.of("CCY")),
                f("fixedRate", 7, "Rate", FieldType.DECIMAL, FieldRole.BUSINESS, false, true, List.of()),
                f("floatingRate", 8, "Rate", FieldType.DECIMAL, FieldRole.BUSINESS, false, true,
                        List.of("Floating Rate")),
                text("fixedFrequency", 9, "Fixed Frequency"),
                text("floatingFrequency", 10, "Floating Frequency"),
                text("dayCount", 11, "Day Count"),
                text("businessDayConvention", 12, "BDC"),
                text("paymentCalendar", 13, "Calendar"),
                text("clearingHouse", 14, "Clearing House"),
                text("clearingMember", 15, "Clearing Member"),
                text("account", 16, "Account"),
                text("counterparty", 17, "Counterparty"),
                text("direction", 18, "Direction"),
                text("upi", 19, "UPI"),
                text("reportingParty", 20, "Reporting Party"),
                text("tradeState", 21, "Trade State"),
                f("novationDate", 22, "Novation Date", FieldType.DATE, FieldRole.BUSINESS, false, true, List.of()),
                f("portfolio", 23, "Portfolio", FieldType.TEXT, FieldRole.BUSINESS, false, false, List.of()),
                f("usPerson", 24, "US Person", FieldType.BOOLEAN, FieldRole.BUSINESS, false, false, List.of())));
    }

    public static ProductSchema small() {
        return ProductSchema.assemble("SMALL", "Small", "", List.of(
                text("a", 1, "A"), text("b", 2, "B"), text("c", 3, "C")));
    }

    public static ProductSchema duplicateRate() {
        return ProductSchema.assemble("DUP", "Duplicate rate", "", List.of(
                keyField("tradeId", 1, "Trade ID", false, List.of()),
                text("fixedRate", 2, "Rate"),
                text("floatingRate", 3, "Rate")));
    }

    public static ProductSchema analyzed() {
        return ProductSchema.assemble("AN", "Analyzed", "", List.of(
                keyField("tradeId", 1, "Trade ID", true, List.of()),
                f("tradeDate", 2, "Trade Date", FieldType.DATE, FieldRole.BUSINESS, true, true, List.of()),
                f("notional", 3, "Notional", FieldType.DECIMAL, FieldRole.BUSINESS, true, true, List.of())));
    }
}
