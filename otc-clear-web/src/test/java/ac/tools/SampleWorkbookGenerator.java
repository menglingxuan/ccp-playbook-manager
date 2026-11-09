package ac.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Regenerates the demo files under {@code samples/}.
 *
 * <p>Run with {@code gradle :otc-clear-web:generateSamples}. Formula cells are evaluated before the
 * workbook is written so the reader can pick up cached values.
 */
public final class SampleWorkbookGenerator {

    /** Number of data rows in the wide worksheet. */
    public static final int WIDE_ROW_COUNT = 200;
    /** Number of data rows in the wide CSV. */
    public static final int CSV_ROW_COUNT = 100;
    /** Number of columns in the wide worksheet. */
    public static final int WIDE_COLUMN_COUNT = 70;
    /** Number of columns in the wide CSV: 4 managed columns plus the 66 CCSIRS trade columns. */
    public static final int CSV_COLUMN_COUNT = 70;
    /** Logical name of the wide worksheet. */
    public static final String WIDE_SHEET_NAME = "IRS_Wide";
    /** File name of the wide CCSIRS CSV. */
    public static final String WIDE_CSV_FILE = "ccsirs_wide.csv";

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 1, 1);
    private static final String[] CURRENCIES = {"USD", "EUR", "GBP", "JPY", "HKD"};
    private static final String[] SCENARIOS = {"REGRESSION", "SMOKE", "UAT", "PERF"};
    private static final String[] FREQUENCIES = {"ANNUAL", "SEMI", "QUARTERLY"};
    private static final String[] FLOAT_FREQUENCIES = {"SEMI", "QUARTERLY", "MONTHLY"};
    private static final String[] REGIONS = {"APAC", "EMEA", "AMER"};
    private static final String[] DESKS = {"RATES", "XCCY", "FLOW"};
    private static final String[] STRATEGIES = {"HEDGE", "PROP", "CLIENT"};
    private static final String[] SEGMENTS = {"BANK", "ASSET MANAGER", "CORPORATE", "INSURANCE"};
    private static final String[] REGIMES = {"EMIR", "HKMA", "CFTC"};
    private static final String[] VENUES = {"HKTR", "DTCC", "REGIS-TR"};
    private static final String[] FIXING_SOURCES = {"REUTERS", "BLOOMBERG", "MANUAL"};
    private static final String[] ROLL_CONVENTIONS = {"IMM", "MODFOLLOWING"};
    private static final String[] STUB_CONVENTIONS = {"SHORT", "LONG", "NONE"};
    private static final String[] AMORTIZATION = {"NONE", "LINEAR", "CUSTOM"};
    private static final String[] BOOKING_SYSTEMS = {"MWIRE", "SUMMIT", "MANUAL"};

    /** IRS sample: two managed columns, the complete 24 business columns and one unmapped column. */
    private static final String IRS_CSV = """
            _TestCaseId,_Remarks,Report Timestamp,Trade ID,Trade Date,Effective Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Fixed Frequency,Floating Frequency,Day Count,BDC,Calendar,Clearing House,Clearing Member,Account,Counterparty,Direction,UPI,Reporting Party,Trade State,Novation Date,Portfolio,US Person,Extra Ref
            TC-IRS-0001,imported from csv,2026-11-09T20:34:00.970,IRS-1001,2026-01-05,2026-01-07,2031-01-05,"1,500,000.00",USD,0.0325,0.0425,ANNUAL,SEMI,ACT/360,MODIFIED FOLLOWING,TARGET,HKEX OTC CLEAR,CM-001,ACC-10001,CP-001,BUY,UPI-1001,PARTY-A,CLEARED,2026-01-12,PF-01,N,REF-A
            TC-IRS-0002,imported from csv,2026-11-09T20:35:00.12,IRS-1002,2026-02-10,2026-02-12,2031-02-10,2000000,EUR,0.0250,0.0350,ANNUAL,QUARTERLY,ACT/360,MODIFIED FOLLOWING,TARGET,HKEX OTC CLEAR,CM-002,ACC-10002,CP-002,SELL,UPI-1002,PARTY-B,CLEARED,2026-02-17,PF-02,Y,REF-B
            TC-IRS-0003,imported from csv,2026-11-09T20:36:00.45,IRS-1003,2026-03-15,2026-03-17,2031-03-15,750000,GBP,0.0180,0.0280,SEMI,SEMI,ACT/365,MODIFIED FOLLOWING,LONDON,HKEX OTC CLEAR,CM-003,ACC-10003,CP-003,BUY,UPI-1003,PARTY-A,CLEARED,2026-03-20,PF-03,N,REF-C
            """;

    /** OIS sample: no managed column, the complete 20 business columns. */
    private static final String OIS_CSV = """
            Trade ID,Trade Date,Effective Date,Maturity Date,Notional,Notional Currency,Rate,Compounding,Payment Frequency,Day Count,BDC,Calendar,Clearing House,Clearing Member,Account,Counterparty,Direction,UPI,Trade State,Portfolio
            OIS-2001,2026-01-06,2026-01-08,2027-01-06,5000000,USD,0.04125,DAILY,ANNUAL,ACT/360,MODIFIED FOLLOWING,TARGET,HKEX OTC CLEAR,CM-001,ACC-20001,CP-001,BUY,UPI-2001,CLEARED,PF-01
            OIS-2002,2026-02-07,2026-02-09,2027-02-07,3000000,EUR,0.03250,DAILY,ANNUAL,ACT/360,MODIFIED FOLLOWING,TARGET,HKEX OTC CLEAR,CM-002,ACC-20002,CP-002,SELL,UPI-2002,CLEARED,PF-02
            """;

    /** Management columns placed left of the IRS trade block. */
    private static final String[] WIDE_LEFT_MANAGED = {"_TestCaseId", "_Remarks", "Scenario Group", "Priority"};

    /** Sheet that mirrors the whole MW/IRS definition, trade block included. */
    public static final String STANDARD_SHEET_NAME = "IRS.Standard";

    /** First business column of the MW/IRS template; it opens the trade block. */
    public static final String REPORT_TIMESTAMP_HEADER = "Report Timestamp";

    /** IRS trade columns in schema order; "Rate" appears twice on purpose. */
    private static final String[] WIDE_TRADE_HEADERS = {
            "Trade ID", "Trade Date", "Effective Date", "Maturity Date", "Notional", "Notional Currency",
            "Rate", "Rate", "Fixed Frequency", "Floating Frequency", "Day Count", "BDC", "Calendar",
            "Clearing House", "Clearing Member", "Account", "Counterparty", "Direction", "UPI",
            "Reporting Party", "Trade State", "Novation Date", "Portfolio", "US Person"};

    /** Business columns placed right of the IRS trade block. */
    private static final String[] WIDE_RIGHT_MANAGED = {
            "Booking System", "Environment", "Region", "Legal Entity", "Trader", "Sales Person", "Desk",
            "Book", "Portfolio 2", "Strategy", "Client Segment", "Client Name", "ISIN", "LEI", "UTI",
            "Clearing Broker", "Give-up", "Compression ID", "Regime", "Reporting Venue", "Collateral Flag",
            "Margin Type", "Haircut", "Credit Curve", "Discount Curve", "Forecast Curve", "Fixing Source",
            "Roll Convention", "Stub Convention", "Payment Lag", "Holiday Calendar 2", "Reset Calendar 2",
            "Amortization", "Step-up", "Cap", "Floor", "Knock-out", "Barrier", "Notes 2", "Notes 3",
            "Notional Check", "Audit Trail Ref"};

    /** CCSIRS management columns placed left of the trade block. */
    private static final String[] CSV_LEFT_MANAGED = {"_TestCaseId", "_Remarks", "Scenario Group", "Priority"};

    /** CCSIRS trade columns in schema order; the first 50 columns are the required ones. */
    private static final String[] CSV_TRADE_HEADERS = {
            "Trade ID", "Trade Date", "Effective Date", "Maturity Date", "Notional", "Notional Currency",
            "Notional 2", "Notional Currency 2", "Direction", "Fixed Rate", "Floating Rate", "Basis Spread",
            "Fixed Frequency", "Floating Frequency", "Fixed Day Count", "Floating Day Count",
            "Payment Calendar", "Reset Calendar", "Payment BDC", "Reset BDC", "Trade State",
            "Clearing House", "Clearing Member", "Account", "Counterparty", "Counterparty LEI",
            "Reporting Party", "UPI", "UTI", "Portfolio", "Trade Type", "Product", "Swap Sub Type",
            "Settlement Currency", "Settlement Date", "Fixed Leg Payer", "Floating Leg Payer",
            "Fixed Leg Notional", "Floating Leg Notional", "Initial Exchange Rate", "Final Exchange Rate",
            "Exchange Rate Source", "Valuation Date", "Collateral Type", "Margin Agreement",
            "Booking System", "Execution Venue", "Confirmation Method", "Reportable", "US Person",
            "Termination Date", "Novation Date", "Compression Date", "Amendment Date", "Platform",
            "Broker", "Trader", "Sales Person", "Strategy", "Hedge Type", "Risk Class", "Comment",
            "External Reference", "Settlement Method", "Netting Set", "Regulatory Regime"};

    private SampleWorkbookGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args.length > 0 ? args[0] : "samples");
        generate(output);
        System.out.println("Samples written to " + output.toAbsolutePath());
    }

    /** Writes every sample file into {@code output}, creating the directory when needed. */
    public static void generate(Path output) throws IOException {
        Files.createDirectories(output);
        writeIrsWorkbook(output.resolve("irs_workbook.xlsx"));
        Files.writeString(output.resolve("irs_cases.csv"), IRS_CSV, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("ois_cases.csv"), OIS_CSV, StandardCharsets.UTF_8);
        Files.writeString(output.resolve(WIDE_CSV_FILE), wideCsv(), StandardCharsets.UTF_8);
    }

    private static void writeIrsWorkbook(Path file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss.000"));

            writeNotesSheet(workbook);
            writeIrsStandardSheet(workbook, dateStyle, dateTimeStyle);
            writeIrsSheet(workbook, dateStyle);
            writeOisSheet(workbook, dateStyle);
            writeWideSheet(workbook, dateStyle);

            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(out);
        }
    }

    /**
     * Writes the standard IRS sheet: the two system managed columns, the required Report Timestamp,
     * the complete IRS trade block in schema order, then one unmapped column.
     */
    private static void writeIrsStandardSheet(Workbook workbook, CellStyle dateStyle,
            CellStyle dateTimeStyle) {
        Sheet sheet = workbook.createSheet(STANDARD_SHEET_NAME);
        Row header = sheet.createRow(0);
        int column = 0;
        header.createCell(column++).setCellValue("_TestCaseId");
        header.createCell(column++).setCellValue("_Remarks");
        header.createCell(column++).setCellValue(REPORT_TIMESTAMP_HEADER);
        for (String name : WIDE_TRADE_HEADERS) {
            header.createCell(column++).setCellValue(name);
        }
        header.createCell(column).setCellValue("Extra Ref");

        String[][] rows = {
                {"TC-IRS-0001", "standard row 1", "2026-11-09T20:34:00.970", "IRS-1001", "2026-01-05",
                        "2026-01-07", "2031-01-05", "1500000", "USD", "0.0325", "0.0425", "ANNUAL", "SEMI",
                        "ACT/360", "MODIFIED FOLLOWING", "TARGET", "HKEX OTC CLEAR", "CM-001", "ACC-10001",
                        "CP-001", "BUY", "UPI-1001", "PARTY-A", "CLEARED", "2026-01-12", "PF-01", "N",
                        "REF-A"},
                {"TC-IRS-0002", "standard row 2", "2026-11-09T20:35:00.120", "IRS-1002", "2026-02-10",
                        "2026-02-12", "2031-02-10", "2000000", "EUR", "0.0250", "0.0350", "ANNUAL",
                        "QUARTERLY", "ACT/360", "MODIFIED FOLLOWING", "TARGET", "HKEX OTC CLEAR", "CM-002",
                        "ACC-10002", "CP-002", "SELL", "UPI-1002", "PARTY-B", "CLEARED", "2026-02-17",
                        "PF-02", "Y", "REF-B"},
                {"TC-IRS-0003", "standard row 3", "2026-11-09T20:36:00.450", "IRS-1003", "2026-03-15",
                        "2026-03-17", "2031-03-15", "750000", "GBP", "0.0180", "0.0280", "SEMI", "SEMI",
                        "ACT/365", "MODIFIED FOLLOWING", "LONDON", "HKEX OTC CLEAR", "CM-003", "ACC-10003",
                        "CP-003", "BUY", "UPI-1003", "PARTY-A", "CLEARED", "2026-03-20", "PF-03", "N",
                        "REF-C"}};

        for (int index = 0; index < rows.length; index++) {
            Row row = sheet.createRow(index + 1);
            String[] values = rows[index];
            for (int cell = 0; cell < values.length; cell++) {
                if (cell == 2) {
                    setDateTime(row.createCell(cell), LocalDateTime.parse(values[cell]), dateTimeStyle);
                } else if (cell == 4 || cell == 5 || cell == 6 || cell == 24) {
                    setDate(row.createCell(cell), LocalDate.parse(values[cell]), dateStyle);
                } else if (cell == 7 || cell == 9 || cell == 10) {
                    row.createCell(cell).setCellValue(Double.parseDouble(values[cell]));
                } else {
                    row.createCell(cell).setCellValue(values[cell]);
                }
            }
        }
    }

    private static void writeNotesSheet(Workbook workbook) {        Sheet notes = workbook.createSheet("说明");
        notes.createRow(0).createCell(0).setCellValue("OTC Clear 测试数据样例");
        notes.createRow(1).createCell(0).setCellValue("IRS sheet 的表头在第 3 行，交易字段从第 3 列开始。");
        notes.createRow(2).createCell(0).setCellValue("Notional 为公式列，导入时保留公式文本与缓存值。");
        notes.createRow(3).createCell(0).setCellValue(
                "IRS_Wide sheet 为 70 列 x " + WIDE_ROW_COUNT + " 行宽表：左侧 4 个管理列、24 个交易列、右侧 42 个管理列。");
    }

    private static void writeIrsSheet(Workbook workbook, CellStyle dateStyle) {
        Sheet sheet = workbook.createSheet("IRS");
        sheet.createRow(0).createCell(0).setCellValue("IRS test cases - 2026 batch");
        sheet.createRow(1).createCell(0).setCellValue("Prepared for regression");

        String[] headers = {"_TestCaseId", "_Remarks", "Trade ID", "Trade Date", "Maturity Date", "Notional",
                "Notional Currency", "Rate", "Rate", "Direction", "Extra Ref"};
        Row header = sheet.createRow(2);
        for (int index = 0; index < headers.length; index++) {
            header.createCell(index).setCellValue(headers[index]);
        }

        String[][] rows = {
                {"TC-IRS-1001", "formula notional", "IRS-9001", "2026-04-01", "2031-04-01", "1000000",
                        "USD", "0.031", "0.041", "BUY", "REF-1"},
                {"TC-IRS-1002", "formula notional", "IRS-9002", "2026-04-02", "2031-04-02", "2500000",
                        "EUR", "0.027", "0.037", "SELL", "REF-2"},
                {"TC-IRS-1003", "formula notional", "IRS-9003", "2026-04-03", "2031-04-03", "750000",
                        "GBP", "0.019", "0.029", "BUY", "REF-3"},
        };

        for (int index = 0; index < rows.length; index++) {
            Row row = sheet.createRow(3 + index);
            String[] values = rows[index];
            row.createCell(0).setCellValue(values[0]);
            row.createCell(1).setCellValue(values[1]);
            row.createCell(2).setCellValue(values[2]);
            setDate(row.createCell(3), LocalDate.parse(values[3]), dateStyle);
            setDate(row.createCell(4), LocalDate.parse(values[4]), dateStyle);
            row.createCell(5).setCellFormula(values[5] + "*1");
            row.createCell(6).setCellValue(values[6]);
            row.createCell(7).setCellValue(Double.parseDouble(values[7]));
            row.createCell(8).setCellValue(Double.parseDouble(values[8]));
            row.createCell(9).setCellValue(values[9]);
            row.createCell(10).setCellValue(values[10]);
        }
    }

    private static void writeOisSheet(Workbook workbook, CellStyle dateStyle) {
        Sheet ois = workbook.createSheet("OIS");
        String[] oisHeaders = {"Trade ID", "Trade Date", "Maturity Date", "Notional", "Notional Currency",
                "Rate", "Compounding", "Payment Frequency", "Day Count", "BDC", "Direction"};
        Row oisHeader = ois.createRow(0);
        for (int index = 0; index < oisHeaders.length; index++) {
            oisHeader.createCell(index).setCellValue(oisHeaders[index]);
        }
        Row oisRow = ois.createRow(1);
        oisRow.createCell(0).setCellValue("OIS-3001");
        setDate(oisRow.createCell(1), LocalDate.of(2026, 6, 1), dateStyle);
        setDate(oisRow.createCell(2), LocalDate.of(2027, 6, 1), dateStyle);
        oisRow.createCell(3).setCellValue(4000000);
        oisRow.createCell(4).setCellValue("USD");
        oisRow.createCell(5).setCellValue(0.0405);
        oisRow.createCell(6).setCellValue("DAILY");
        oisRow.createCell(7).setCellValue("ANNUAL");
        oisRow.createCell(8).setCellValue("ACT/360");
        oisRow.createCell(9).setCellValue("MODIFIED FOLLOWING");
        oisRow.createCell(10).setCellValue("BUY");
    }

    /**
     * Writes the wide worksheet: title row, header row, then {@link #WIDE_ROW_COUNT} data rows.
     * The trade block keeps the IRS schema order so positional alignment stays exact.
     */
    private static void writeWideSheet(Workbook workbook, CellStyle dateStyle) {
        Sheet sheet = workbook.createSheet(WIDE_SHEET_NAME);
        sheet.createRow(0).createCell(0).setCellValue("IRS wide sheet - " + WIDE_COLUMN_COUNT
                + " columns x " + WIDE_ROW_COUNT + " rows (4 managed | 24 trade | 42 managed)");

        Row header = sheet.createRow(1);
        int column = 0;
        for (String name : WIDE_LEFT_MANAGED) {
            header.createCell(column++).setCellValue(name);
        }
        for (String name : WIDE_TRADE_HEADERS) {
            header.createCell(column++).setCellValue(name);
        }
        for (String name : WIDE_RIGHT_MANAGED) {
            header.createCell(column++).setCellValue(name);
        }

        for (int index = 1; index <= WIDE_ROW_COUNT; index++) {
            Row row = sheet.createRow(1 + index);
            writeWideDataRow(row, index, dateStyle);
        }
    }

    private static void writeWideDataRow(Row row, int index, CellStyle dateStyle) {
        LocalDate tradeDate = BASE_DATE.plusDays(index);
        String currency = CURRENCIES[(index - 1) % CURRENCIES.length];
        double amount = 1_000_000 + index * 12_500.0;
        double fixedRate = 0.01 + index * 0.00005;

        int column = 0;
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "TC-IRS-W-%04d", index));
        row.createCell(column++).setCellValue("wide row " + index);
        row.createCell(column++).setCellValue(SCENARIOS[(index - 1) % SCENARIOS.length]);
        row.createCell(column++).setCellValue("P" + (1 + index % 3));

        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "IRS-W-%04d", index));
        setDate(row.createCell(column++), tradeDate, dateStyle);
        setDate(row.createCell(column++), tradeDate.plusDays(2), dateStyle);
        setDate(row.createCell(column++), tradeDate.plusYears(5), dateStyle);
        row.createCell(column++).setCellFormula(String.format(Locale.ROOT, "%.2f*1", amount));
        row.createCell(column++).setCellValue(currency);
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "%.6f", fixedRate));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "%.6f", fixedRate + 0.01));
        row.createCell(column++).setCellValue(FREQUENCIES[(index - 1) % FREQUENCIES.length]);
        row.createCell(column++).setCellValue(FLOAT_FREQUENCIES[(index - 1) % FLOAT_FREQUENCIES.length]);
        row.createCell(column++).setCellValue("ACT/360");
        row.createCell(column++).setCellValue("MODIFIED FOLLOWING");
        row.createCell(column++).setCellValue("TARGET");
        row.createCell(column++).setCellValue("HKEX OTC CLEAR");
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "CM-%03d", 1 + index % 12));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "ACC-%05d", 10000 + index));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "CP-%03d", 1 + index % 40));
        row.createCell(column++).setCellValue(index % 2 == 0 ? "SELL" : "BUY");
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "UPI-%06d", index));
        row.createCell(column++).setCellValue(index % 3 == 0 ? "PARTY-B" : "PARTY-A");
        row.createCell(column++).setCellValue("CLEARED");
        setDate(row.createCell(column++), tradeDate.plusDays(7), dateStyle);
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "PF-%02d", 1 + index % 8));
        row.createCell(column++).setCellValue(index % 4 == 0 ? "Y" : "N");

        row.createCell(column++).setCellValue(BOOKING_SYSTEMS[index % BOOKING_SYSTEMS.length]);
        row.createCell(column++).setCellValue("UAT");
        row.createCell(column++).setCellValue(REGIONS[(index - 1) % REGIONS.length]);
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "LE-%03d", 1 + index % 5));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "TRADER%02d", 1 + index % 15));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "SALES%02d", 1 + index % 9));
        row.createCell(column++).setCellValue(DESKS[(index - 1) % DESKS.length]);
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "BOOK-%02d", 1 + index % 20));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "PF-%02d", 1 + index % 8));
        row.createCell(column++).setCellValue(STRATEGIES[(index - 1) % STRATEGIES.length]);
        row.createCell(column++).setCellValue(SEGMENTS[(index - 1) % SEGMENTS.length]);
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "CLIENT-%03d", 1 + index % 50));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "XS%010d", 1_000_000 + index));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "LEI%016d", 5_000_000L + index));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "UTI-%012d", 700_000L + index));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "BROKER-%02d", 1 + index % 6));
        row.createCell(column++).setCellValue(index % 5 == 0 ? "Y" : "N");
        row.createCell(column++).setCellValue(index % 7 == 0
                ? String.format(Locale.ROOT, "CMP-%04d", index) : "");
        row.createCell(column++).setCellValue(REGIMES[(index - 1) % REGIMES.length]);
        row.createCell(column++).setCellValue(VENUES[(index - 1) % VENUES.length]);
        row.createCell(column++).setCellValue(index % 3 == 0 ? "Y" : "N");
        row.createCell(column++).setCellValue(index % 2 == 0 ? "VM+IM" : "VM");
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "%.4f", 0.005 + (index % 10) * 0.001));
        row.createCell(column++).setCellValue(String.format(Locale.ROOT, "CC-%02d", 1 + index % 7));
        row.createCell(column++).setCellValue(currency + "-SOFR");
        row.createCell(column++).setCellValue(currency + "-SOFR-3M");
        row.createCell(column++).setCellValue(FIXING_SOURCES[(index - 1) % FIXING_SOURCES.length]);
        row.createCell(column++).setCellValue(ROLL_CONVENTIONS[(index - 1) % ROLL_CONVENTIONS.length]);
        row.createCell(column++).setCellValue(STUB_CONVENTIONS[(index - 1) % STUB_CONVENTIONS.length]);
        row.createCell(column++).setCellValue(String.valueOf(index % 3));
        row.createCell(column++).setCellValue("TARGET,NY");
        row.createCell(column++).setCellValue("TARGET");
        row.createCell(column++).setCellValue(AMORTIZATION[(index - 1) % AMORTIZATION.length]);
        row.createCell(column++).setCellValue("N");
        row.createCell(column++).setCellValue(index % 6 == 0 ? "0.05" : "");
        row.createCell(column++).setCellValue("");
        row.createCell(column++).setCellValue("N");
        row.createCell(column++).setCellValue("");
        row.createCell(column++).setCellValue("auto-generated");
        row.createCell(column++).setCellValue("");
        row.createCell(column++).setCellFormula(String.format(Locale.ROOT, "%.2f*1", amount));
        row.createCell(column).setCellValue(String.format(Locale.ROOT, "AUD-%06d", index));
    }

    /**
     * Builds the 70 column x 100 row CCSIRS CSV: 4 management columns followed by the complete
     * 66 column trade block, so every seeded CCSIRS field has a value.
     */
    private static String wideCsv() {
        StringBuilder sb = new StringBuilder(256 * 1024);
        appendCsvLine(sb, concat(CSV_LEFT_MANAGED, CSV_TRADE_HEADERS));
        for (int index = 1; index <= CSV_ROW_COUNT; index++) {
            appendCsvLine(sb, csvRow(index));
        }
        return sb.toString();
    }

    private static String[] csvRow(int index) {
        LocalDate tradeDate = BASE_DATE.plusDays(index);
        String currency = CURRENCIES[(index - 1) % CURRENCIES.length];
        String currency2 = CURRENCIES[index % CURRENCIES.length];
        String notional = String.format(Locale.ROOT, "%d.00", 2_000_000L + index * 25_000L);
        String notional2 = String.format(Locale.ROOT, "%d.00", 1_600_000L + index * 20_000L);
        String fixedRate = String.format(Locale.ROOT, "%.6f", 0.015 + index * 0.00004);
        String floatingRate = String.format(Locale.ROOT, "%.6f", 0.025 + index * 0.00004);
        String basisSpread = String.format(Locale.ROOT, "%.6f", 0.0005 + index * 0.000002);
        String counterparty = String.format(Locale.ROOT, "CP-%03d", 1 + index % 40);
        String exchangeRate = String.format(Locale.ROOT, "%.6f", 7.7500 + (index % 100) * 0.0015);

        return new String[] {
                String.format(Locale.ROOT, "TC-CCS-%04d", index),
                "wide csv row " + index,
                SCENARIOS[(index - 1) % SCENARIOS.length],
                "P" + (1 + index % 3),
                String.format(Locale.ROOT, "CCS-W-%04d", index),
                tradeDate.toString(),
                tradeDate.plusDays(2).toString(),
                tradeDate.plusYears(10).toString(),
                notional,
                currency,
                notional2,
                currency2,
                index % 2 == 0 ? "SELL" : "BUY",
                fixedRate,
                floatingRate,
                basisSpread,
                FREQUENCIES[(index - 1) % FREQUENCIES.length],
                FLOAT_FREQUENCIES[(index - 1) % FLOAT_FREQUENCIES.length],
                "ACT/360",
                "ACT/365",
                "TARGET",
                "TARGET,NY",
                "MODIFIED FOLLOWING",
                "MODIFIED FOLLOWING",
                "CLEARED",
                "HKEX OTC CLEAR",
                String.format(Locale.ROOT, "CM-%03d", 1 + index % 12),
                String.format(Locale.ROOT, "ACC-%05d", 20000 + index),
                counterparty,
                String.format(Locale.ROOT, "LEI%016d", 5_000_000L + index),
                index % 3 == 0 ? "PARTY-B" : "PARTY-A",
                String.format(Locale.ROOT, "UPI-CCS-%06d", index),
                String.format(Locale.ROOT, "UTI-%012d", 700_000L + index),
                String.format(Locale.ROOT, "PF-%02d", 1 + index % 8),
                "CROSS CURRENCY SWAP",
                "CCSIRS",
                "FIXED FLOAT",
                currency,
                tradeDate.plusDays(2).toString(),
                "PARTY-A",
                "PARTY-B",
                notional,
                notional2,
                exchangeRate,
                exchangeRate,
                "REUTERS",
                tradeDate.toString(),
                "CASH",
                index % 2 == 0 ? "VM+IM" : "VM",
                BOOKING_SYSTEMS[index % BOOKING_SYSTEMS.length],
                VENUES[(index - 1) % VENUES.length],
                index % 4 == 0 ? "MANUAL" : "ELECTRONIC",
                "Y",
                index % 5 == 0 ? "Y" : "N",
                tradeDate.plusYears(5).toString(),
                tradeDate.plusDays(30).toString(),
                tradeDate.plusDays(45).toString(),
                tradeDate.plusDays(60).toString(),
                "MW",
                String.format(Locale.ROOT, "BROKER-%02d", 1 + index % 6),
                String.format(Locale.ROOT, "TRADER%02d", 1 + index % 15),
                String.format(Locale.ROOT, "SALES%02d", 1 + index % 9),
                STRATEGIES[(index - 1) % STRATEGIES.length],
                index % 2 == 0 ? "HEDGE" : "PROP",
                "RATES",
                String.format(Locale.ROOT, "auto generated row %04d", index),
                String.format(Locale.ROOT, "EXT-%06d", index),
                index % 3 == 0 ? "PHYSICAL" : "NET",
                String.format(Locale.ROOT, "NS-%03d", 1 + index % 20),
                REGIMES[(index - 1) % REGIMES.length]};
    }

    private static void appendCsvLine(StringBuilder sb, String[] values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                sb.append(',');
            }
            sb.append(escapeCsv(values[index]));
        }
        sb.append('\n');
    }

    private static String escapeCsv(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0
                && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static String[] concat(String[] first, String[] second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static String[] concat(String[] first, String[] second, String[] third) {
        String[] all = new String[first.length + second.length + third.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        System.arraycopy(third, 0, all, first.length + second.length, third.length);
        return all;
    }

    private static void setDate(Cell cell, LocalDate value, CellStyle dateStyle) {
        cell.setCellValue(value);
        cell.setCellStyle(dateStyle);
    }

    private static void setDateTime(Cell cell, LocalDateTime value, CellStyle dateTimeStyle) {
        cell.setCellValue(value);
        cell.setCellStyle(dateTimeStyle);
    }
}
