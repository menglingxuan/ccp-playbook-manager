package ac.domain.product.products;

import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import ac.domain.product.ProductDefinition;
import ac.domain.product.ProductField;

/** Interest rate swap product definition (sample field set, pending business confirmation). */
@ProductDefinition(code = "IRS", name = "Interest Rate Swap", description = "Fixed versus floating interest rate swap")
@ProductField(key = "tradeId", order = 1, header = "Trade ID", aliases = {"TradeID", "Trade Ref"},
        role = FieldRole.KEY, required = true, clonePattern = "{base}-{seq:02}")
@ProductField(key = "tradeDate", order = 2, header = "Trade Date", type = FieldType.DATE, required = true)
@ProductField(key = "effectiveDate", order = 3, header = "Effective Date", type = FieldType.DATE)
@ProductField(key = "maturityDate", order = 4, header = "Maturity Date", type = FieldType.DATE, required = true)
@ProductField(key = "notional", order = 5, header = "Notional", type = FieldType.DECIMAL, required = true,
        format = "0.00")
@ProductField(key = "notionalCurrency", order = 6, header = "Notional Currency", aliases = {"CCY"},
        required = true)
@ProductField(key = "fixedRate", order = 7, header = "Rate", type = FieldType.DECIMAL, format = "0.000000")
@ProductField(key = "floatingRate", order = 8, header = "Rate", type = FieldType.DECIMAL,
        aliases = {"Floating Rate"}, format = "0.000000")
@ProductField(key = "fixedFrequency", order = 9, header = "Fixed Frequency")
@ProductField(key = "floatingFrequency", order = 10, header = "Floating Frequency")
@ProductField(key = "dayCount", order = 11, header = "Day Count", aliases = {"Day Count Convention"})
@ProductField(key = "businessDayConvention", order = 12, header = "BDC",
        aliases = {"Business Day Convention"})
@ProductField(key = "paymentCalendar", order = 13, header = "Calendar", aliases = {"Payment Calendar"})
@ProductField(key = "clearingHouse", order = 14, header = "Clearing House")
@ProductField(key = "clearingMember", order = 15, header = "Clearing Member")
@ProductField(key = "account", order = 16, header = "Account")
@ProductField(key = "counterparty", order = 17, header = "Counterparty")
@ProductField(key = "direction", order = 18, header = "Direction", aliases = {"Trade Direction"})
@ProductField(key = "upi", order = 19, header = "UPI")
@ProductField(key = "reportingParty", order = 20, header = "Reporting Party")
@ProductField(key = "tradeState", order = 21, header = "Trade State", aliases = {"Status"})
@ProductField(key = "novationDate", order = 22, header = "Novation Date", type = FieldType.DATE)
@ProductField(key = "portfolio", order = 23, header = "Portfolio", businessKey = false)
@ProductField(key = "usPerson", order = 24, header = "US Person", type = FieldType.BOOLEAN,
        businessKey = false)
public final class IrsCase {

    private IrsCase() {
    }
}
