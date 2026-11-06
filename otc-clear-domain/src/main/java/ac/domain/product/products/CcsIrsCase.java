package ac.domain.product.products;

import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import ac.domain.product.ProductDefinition;
import ac.domain.product.ProductField;

/** Cross currency swap product definition (sample field set, pending business confirmation). */
@ProductDefinition(code = "CCSIRS", name = "Cross Currency Swap",
        description = "Cross currency interest rate swap cleared product")
@ProductField(key = "tradeId", order = 1, header = "Trade ID", aliases = {"TradeID"},
        role = FieldRole.KEY, required = true, clonePattern = "{base}-{seq:02}")
@ProductField(key = "tradeDate", order = 2, header = "Trade Date", type = FieldType.DATE, required = true)
@ProductField(key = "effectiveDate", order = 3, header = "Effective Date", type = FieldType.DATE)
@ProductField(key = "maturityDate", order = 4, header = "Maturity Date", type = FieldType.DATE, required = true)
@ProductField(key = "notional", order = 5, header = "Notional", type = FieldType.DECIMAL, required = true,
        format = "0.00")
@ProductField(key = "notionalCurrency", order = 6, header = "Notional Currency", aliases = {"CCY"},
        required = true)
@ProductField(key = "notional2", order = 7, header = "Notional 2", type = FieldType.DECIMAL, required = true,
        aliases = {"Second Notional"}, format = "0.00")
@ProductField(key = "notionalCurrency2", order = 8, header = "Notional Currency 2", aliases = {"CCY 2"},
        required = true)
@ProductField(key = "fixedRate", order = 9, header = "Rate", type = FieldType.DECIMAL, format = "0.000000")
@ProductField(key = "floatingRate", order = 10, header = "Rate", type = FieldType.DECIMAL,
        aliases = {"Floating Rate"}, format = "0.000000")
@ProductField(key = "basisSpread", order = 11, header = "Basis Spread", type = FieldType.DECIMAL,
        format = "0.000000")
@ProductField(key = "fixedFrequency", order = 12, header = "Fixed Frequency")
@ProductField(key = "floatingFrequency", order = 13, header = "Floating Frequency")
@ProductField(key = "dayCount", order = 14, header = "Day Count")
@ProductField(key = "businessDayConvention", order = 15, header = "BDC",
        aliases = {"Business Day Convention"})
@ProductField(key = "paymentCalendar", order = 16, header = "Calendar", aliases = {"Payment Calendar"})
@ProductField(key = "resetCalendar", order = 17, header = "Reset Calendar")
@ProductField(key = "clearingHouse", order = 18, header = "Clearing House")
@ProductField(key = "clearingMember", order = 19, header = "Clearing Member")
@ProductField(key = "account", order = 20, header = "Account")
@ProductField(key = "counterparty", order = 21, header = "Counterparty")
@ProductField(key = "direction", order = 22, header = "Direction")
@ProductField(key = "upi", order = 23, header = "UPI")
@ProductField(key = "tradeState", order = 24, header = "Trade State", aliases = {"Status"})
public final class CcsIrsCase {

    private CcsIrsCase() {
    }
}
