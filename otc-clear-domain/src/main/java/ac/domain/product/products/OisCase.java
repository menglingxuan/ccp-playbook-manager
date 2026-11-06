package ac.domain.product.products;

import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import ac.domain.product.ProductDefinition;
import ac.domain.product.ProductField;

/** Overnight index swap product definition (sample field set, pending business confirmation). */
@ProductDefinition(code = "OIS", name = "Overnight Index Swap", description = "Overnight index swap cleared product")
@ProductField(key = "tradeId", order = 1, header = "Trade ID", aliases = {"TradeID"},
        role = FieldRole.KEY, required = true, clonePattern = "{base}-{seq:02}")
@ProductField(key = "tradeDate", order = 2, header = "Trade Date", type = FieldType.DATE, required = true)
@ProductField(key = "effectiveDate", order = 3, header = "Effective Date", type = FieldType.DATE)
@ProductField(key = "maturityDate", order = 4, header = "Maturity Date", type = FieldType.DATE, required = true)
@ProductField(key = "notional", order = 5, header = "Notional", type = FieldType.DECIMAL, required = true,
        format = "0.00")
@ProductField(key = "notionalCurrency", order = 6, header = "Notional Currency", aliases = {"CCY"},
        required = true)
@ProductField(key = "overnightRate", order = 7, header = "Rate", aliases = {"Overnight Rate"},
        type = FieldType.DECIMAL, format = "0.000000")
@ProductField(key = "compoundingMethod", order = 8, header = "Compounding", aliases = {"Compounding Method"})
@ProductField(key = "paymentFrequency", order = 9, header = "Payment Frequency")
@ProductField(key = "dayCount", order = 10, header = "Day Count")
@ProductField(key = "businessDayConvention", order = 11, header = "BDC",
        aliases = {"Business Day Convention"})
@ProductField(key = "paymentCalendar", order = 12, header = "Calendar", aliases = {"Payment Calendar"})
@ProductField(key = "clearingHouse", order = 13, header = "Clearing House")
@ProductField(key = "clearingMember", order = 14, header = "Clearing Member")
@ProductField(key = "account", order = 15, header = "Account")
@ProductField(key = "counterparty", order = 16, header = "Counterparty")
@ProductField(key = "direction", order = 17, header = "Direction")
@ProductField(key = "upi", order = 18, header = "UPI")
@ProductField(key = "tradeState", order = 19, header = "Trade State", aliases = {"Status"})
@ProductField(key = "portfolio", order = 20, header = "Portfolio", businessKey = false)
public final class OisCase {

    private OisCase() {
    }
}
