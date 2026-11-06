package ac.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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

    private static final String IRS_CSV = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction,Extra Ref
            TC-IRS-0001,imported from csv,IRS-1001,2026-01-05,2031-01-05,"1,500,000.00",USD,0.0325,0.0425,BUY,REF-A
            TC-IRS-0002,imported from csv,IRS-1002,2026-02-10,2031-02-10,2000000,EUR,0.0250,0.0350,SELL,REF-B
            TC-IRS-0003,imported from csv,IRS-1003,2026-03-15,2031-03-15,750000,GBP,0.0180,0.0280,BUY,REF-C
            """;

    private static final String OIS_CSV = """
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Compounding,Payment Frequency,Day Count,BDC,Direction
            OIS-2001,2026-01-06,2027-01-06,5000000,USD,0.04125,DAILY,ANNUAL,ACT/360,MODIFIED FOLLOWING,BUY
            OIS-2002,2026-02-07,2027-02-07,3000000,EUR,0.03250,DAILY,ANNUAL,ACT/360,MODIFIED FOLLOWING,SELL
            """;

    private SampleWorkbookGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args.length > 0 ? args[0] : "samples");
        Files.createDirectories(output);
        writeIrsWorkbook(output.resolve("irs_workbook.xlsx"));
        Files.writeString(output.resolve("irs_cases.csv"), IRS_CSV, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("ois_cases.csv"), OIS_CSV, StandardCharsets.UTF_8);
        System.out.println("Samples written to " + output.toAbsolutePath());
    }

    private static void writeIrsWorkbook(Path file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

            Sheet notes = workbook.createSheet("说明");
            notes.createRow(0).createCell(0).setCellValue("OTC Clear 测试数据样例");
            notes.createRow(1).createCell(0).setCellValue("IRS sheet 的表头在第 3 行，交易字段从第 3 列开始。");
            notes.createRow(2).createCell(0).setCellValue("Notional 为公式列，导入时保留公式文本与缓存值。");

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
                Cell tradeDate = row.createCell(3);
                tradeDate.setCellValue(LocalDate.parse(values[3]));
                tradeDate.setCellStyle(dateStyle);
                Cell maturity = row.createCell(4);
                maturity.setCellValue(LocalDate.parse(values[4]));
                maturity.setCellStyle(dateStyle);
                row.createCell(5).setCellFormula(values[5] + "*1");
                row.createCell(6).setCellValue(values[6]);
                row.createCell(7).setCellValue(Double.parseDouble(values[7]));
                row.createCell(8).setCellValue(Double.parseDouble(values[8]));
                row.createCell(9).setCellValue(values[9]);
                row.createCell(10).setCellValue(values[10]);
            }

            Sheet ois = workbook.createSheet("OIS");
            String[] oisHeaders = {"Trade ID", "Trade Date", "Maturity Date", "Notional", "Notional Currency",
                    "Rate", "Compounding", "Payment Frequency", "Day Count", "BDC", "Direction"};
            Row oisHeader = ois.createRow(0);
            for (int index = 0; index < oisHeaders.length; index++) {
                oisHeader.createCell(index).setCellValue(oisHeaders[index]);
            }
            Row oisRow = ois.createRow(1);
            oisRow.createCell(0).setCellValue("OIS-3001");
            Cell oisDate = oisRow.createCell(1);
            oisDate.setCellValue(LocalDate.of(2026, 6, 1));
            oisDate.setCellStyle(dateStyle);
            Cell oisMaturity = oisRow.createCell(2);
            oisMaturity.setCellValue(LocalDate.of(2027, 6, 1));
            oisMaturity.setCellStyle(dateStyle);
            oisRow.createCell(3).setCellValue(4000000);
            oisRow.createCell(4).setCellValue("USD");
            oisRow.createCell(5).setCellValue(0.0405);
            oisRow.createCell(6).setCellValue("DAILY");
            oisRow.createCell(7).setCellValue("ANNUAL");
            oisRow.createCell(8).setCellValue("ACT/360");
            oisRow.createCell(9).setCellValue("MODIFIED FOLLOWING");
            oisRow.createCell(10).setCellValue("BUY");

            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(out);
        }
    }
}
