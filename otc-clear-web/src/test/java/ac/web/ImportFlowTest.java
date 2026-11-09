package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Upload, preview, import, browse, clone and export through the real HTTP API. */
class ImportFlowTest extends AbstractIntegrationTest {

    private static final String CSV_TEMPLATE = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction,Extra Ref
            TC-1,first,%s,2026-01-02,2031-01-02,"1,500,000.00",USD,0.0325,BUY,ref-1
            TC-2,second,%s-B,2026-02-03,2031-02-03,2000000,EUR,0.025,SELL,ref-2
            """;

    @Test
    void importsClonesAndExports() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tradeId = "IRS-" + suffix;
        String csv = CSV_TEMPLATE.formatted(tradeId, tradeId);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "direction"));

        var upload = api.upload("/api/uploads", "irs_" + suffix + ".csv",
                csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        JsonNode uploadJson = upload.json();
        String uploadId = uploadJson.get("uploadId").asString();
        assertThat(uploadJson.get("sheets")).hasSize(1);

        var preview = api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode previewJson = preview.json().get("preview");
        assertThat(previewJson.get("valid").asBoolean()).isTrue();
        assertThat(previewJson.get("layout").get("headerRow").asInt()).isEqualTo(1);
        assertThat(previewJson.get("layout").get("tradeStartColumn").asInt()).isEqualTo(3);
        assertThat(previewJson.get("stats").get("mappedTradeColumns").asInt()).isEqualTo(7);
        assertThat(previewJson.get("stats").get("managedColumns").asInt()).isEqualTo(2);
        JsonNode firstSample = previewJson.get("sampleRows").get(0);
        assertThat(firstSample.get("values").get("fixedRate").decimalValue().toPlainString()).isEqualTo("0.0325");

        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId,
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode, "headerRow", 1,
                        "tradeStartColumn", 3, "dataStartRow", 2)))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();
        JsonNode importJson = importResponse.json();
        assertThat(importJson.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(importJson.get("totalRows").asInt()).isEqualTo(2);
        String taskId = importJson.get("taskId").asString();
        String batchId = importJson.get("batches").get(0).get("batchId").asString();

        var task = api.get("/api/tasks/" + taskId);
        assertThat(task.ok()).isTrue();
        assertThat(task.json().get("task").get("sourceName").asString()).startsWith("irs_");

        var cases = api.get("/api/batches/" + batchId + "/cases?page=1&size=50");
        assertThat(cases.ok()).isTrue();
        JsonNode caseItems = cases.json().get("items");
        assertThat(caseItems).hasSize(2);
        JsonNode firstCase = caseItems.get(0);
        assertThat(firstCase.get("testCaseId").asString()).isEqualTo("TC-1");
        assertThat(firstCase.get("keyValue").asString()).isEqualTo(tradeId);
        String caseId = firstCase.get("id").asString();

        var vertical = api.get("/api/cases/" + caseId + "/vertical");
        assertThat(vertical.ok()).isTrue();
        JsonNode sections = vertical.json().get("sections");
        JsonNode tradeSection = null;
        for (JsonNode section : sections) {
            if ("trade".equals(section.get("code").asString())) {
                tradeSection = section;
            }
        }
        assertThat(tradeSection).isNotNull();
        assertThat(tradeSection.get("rows")).hasSize(7);

        var clonePreview = api.post("/api/clones/preview", api.toJson(Map.of(
                "caseIds", List.of(caseId), "copies", 2, "template", "{base}-C{seq:02}", "note", "it")));
        assertThat(clonePreview.ok()).as(clonePreview.body()).isTrue();
        assertThat(clonePreview.json().get("totalRows").asInt()).isEqualTo(2);

        var clone = api.post("/api/clones", api.toJson(Map.of(
                "caseIds", List.of(caseId), "copies", 2, "template", "{base}-C{seq:02}", "note", "it")));
        assertThat(clone.ok()).as(clone.body()).isTrue();
        assertThat(clone.json().get("created").asInt()).isEqualTo(2);

        Map<String, Object> exportRequest = Map.of(
                "batchId", batchId,
                "caseIds", List.of(),
                "options", Map.of("delimiter", ";", "quoteAll", true, "includeHeader", true,
                        "lineEnding", "\r\n", "bom", false, "managedColumns", "ALL",
                        "formulaAsValue", true));
        var precheck = api.post("/api/exports/precheck", api.toJson(exportRequest));
        assertThat(precheck.ok()).as(precheck.body()).isTrue();
        assertThat(precheck.json().get("valid").asBoolean()).isTrue();
        JsonNode headers = precheck.json().get("headers");
        assertThat(headers).hasSize(9);
        assertThat(headers.get(7).asString()).isEqualTo("Rate");

        var export = api.post("/api/exports", api.toJson(exportRequest));
        assertThat(export.ok()).as(export.body()).isTrue();
        JsonNode exportJson = export.json();
        assertThat(exportJson.get("rowCount").asInt()).isEqualTo(2);

        String csvText = new String(api.getBytes("/api/exports/" + exportJson.get("exportId").asString()
                + "/download"), StandardCharsets.UTF_8);
        assertThat(csvText).startsWith("\"_TestCaseId\";");
        assertThat(csvText).contains("1500000.00");
        assertThat(csvText.split("\r\n")).hasSize(3);
    }

    @Test
    void reimportingTheSameFileStartsAFreshBatch() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = """
                _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction,Extra Ref
                AGAIN-%s,note,AGAIN-%s,2026-01-02,2031-01-02,1000000,USD,0.03,BUY,ref-1
                """.formatted(suffix, suffix);

        var first = importFile(csv, "again1_" + suffix + ".csv", productCode);
        assertThat(first.json().get("status").asString()).isEqualTo("SUCCEEDED");
        var second = importFile(csv, "again2_" + suffix + ".csv", productCode);

        assertThat(second.json().get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(second.json().get("batchCount").asInt()).isEqualTo(1);
        assertThat(second.json().get("issues").toString())
                .doesNotContain("DUPLICATE_IMPORT")
                .doesNotContain("KEY_DUPLICATE");
        // wizard level column notes are not repeated in the import result
        assertThat(second.json().get("issues").toString())
                .doesNotContain("UNMAPPED_TRADE_COLUMN")
                .doesNotContain("DUPLICATE_HEADER_MAPPING");
    }

    @Test
    void submittingTheSameUploadTwiceIsReportedAsDuplicate() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = """
                Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
                TWICE-%s,2026-01-02,2031-01-02,1000000,USD,0.03,BUY
                """.formatted(suffix);
        var upload = api.upload("/api/uploads", "twice_" + suffix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();
        Map<String, Object> payload = Map.of("uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)));

        assertThat(api.post("/api/imports", api.toJson(payload)).json().get("status").asString())
                .isEqualTo("SUCCEEDED");
        var again = api.post("/api/imports", api.toJson(payload));

        assertThat(again.json().get("issues").toString()).contains("DUPLICATE_IMPORT");
    }

    @Test
    void repeatedTradeIdInsideTheSheetIsReported() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = """
                Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
                SAME-%s,2026-01-02,2031-01-02,1000000,USD,0.03,BUY
                SAME-%s,2026-01-03,2031-01-03,2000000,USD,0.04,SELL
                """.formatted(suffix, suffix);

        var imported = importFile(csv, "samekey_" + suffix + ".csv", productCode);

        assertThat(imported.json().get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(imported.json().get("issues").toString())
                .contains("KEY_DUPLICATE")
                .contains("appears 2 times in the sheet")
                .doesNotContain("already exists");
    }

    @Test
    void dataEndRowLimitsTheImportedRows() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = """
                Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
                END-%s-1,2026-01-02,2031-01-02,1000000,USD,0.03,BUY
                END-%s-2,2026-01-03,2031-01-03,2000000,USD,0.04,SELL
                END-%s-3,2026-01-04,2031-01-04,3000000,USD,0.05,BUY
                """.formatted(suffix, suffix, suffix);
        var upload = api.upload("/api/uploads", "endrow_" + suffix + ".csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();

        // header row 1, data starts at row 2: ending at row 3 keeps the first two data rows
        var preview = api.post("/api/uploads/" + uploadId + "/preview", api.toJson(Map.of(
                "sheetIndex", 0, "productCode", productCode, "dataStartRow", 2, "dataEndRow", 3)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode sheet = preview.json().get("preview");
        assertThat(sheet.get("stats").get("dataRows").asInt()).isEqualTo(2);
        assertThat(sheet.get("sampleRows")).hasSize(2);

        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode,
                        "dataStartRow", 2, "dataEndRow", 3)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        assertThat(imported.json().get("totalRows").asInt()).isEqualTo(2);
    }

    @Test
    void dataEndRowAboveTheStartRowIsRejected() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        var upload = api.upload("/api/uploads", "badrange_" + suffix + ".csv", ("""
                Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
                BAD-%s,2026-01-02,2031-01-02,1000000,USD,0.03,BUY
                """.formatted(suffix)).getBytes(StandardCharsets.UTF_8));

        var preview = api.post("/api/uploads/" + upload.json().get("uploadId").asString() + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode,
                        "dataStartRow", 5, "dataEndRow", 3)));

        assertThat(preview.json().get("preview").get("valid").asBoolean()).isFalse();
        assertThat(preview.body()).contains("INVALID_DATA_RANGE");
    }

    /** Uploads and imports the CSV, returning the import response. */
    private ApiClient.Response importFile(String csv, String fileName, String productCode) {
        var upload = api.upload("/api/uploads", fileName, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        return api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(),
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
    }

    @Test
    void importOptionsControlManagedAndUnmappedColumns() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = """
                _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction,Extra Ref
                OPT-%s,note,OPT-%s,2026-01-02,2031-01-02,1000000,USD,0.03,BUY,ref-1
                """.formatted(suffix, suffix);

        JsonNode withoutExtras = importCase(csv, "opt_" + suffix + ".csv", productCode, Map.of());
        assertThat(withoutExtras.get("managed").get("_TestCaseId").asString()).isEqualTo("OPT-" + suffix);
        assertThat(withoutExtras.get("extras").size()).isZero();

        JsonNode withExtras = importCase(csv, "opt2_" + suffix + ".csv", productCode,
                Map.of("includeUnmappedColumns", true));
        assertThat(withExtras.get("managed").get("_Remarks").asString()).isEqualTo("note");
        assertThat(withExtras.get("extras").get("Extra Ref").asString()).isEqualTo("ref-1");

        JsonNode withoutManaged = importCase(csv, "opt3_" + suffix + ".csv", productCode,
                Map.of("includeManagedColumns", false));
        assertThat(withoutManaged.get("managed").size()).isZero();
        assertThat(withoutManaged.get("testCaseId").asString()).isEqualTo("OPT-" + suffix);
    }

    /** Imports the CSV with the given extra request flags and returns the first case detail. */
    private JsonNode importCase(String csv, String fileName, String productCode, Map<String, Object> flags) {
        var upload = api.upload("/api/uploads", fileName, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        Map<String, Object> payload = new java.util.LinkedHashMap<>(flags);
        payload.put("uploadId", upload.json().get("uploadId").asString());
        payload.put("force", false);
        payload.put("sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)));
        var imported = api.post("/api/imports", api.toJson(payload));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        String batchId = imported.json().get("batches").get(0).get("batchId").asString();
        var cases = api.get("/api/batches/" + batchId + "/cases?page=1&size=5");
        String caseId = cases.json().get("items").get(0).get("id").asString();
        return api.get("/api/cases/" + caseId).json();
    }

    @Test
    void sampleLimitGrowsThePreviewedRows() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        StringBuilder csv = new StringBuilder(
                "Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction\n");
        for (int i = 0; i < 30; i++) {
            csv.append("SMP-%s-%d,2026-01-02,2031-01-02,1000,USD,0.03,BUY%n".formatted(suffix, i));
        }
        var upload = api.upload("/api/uploads", "sample_" + suffix + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();

        JsonNode firstPage = api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode))).json().get("preview");
        assertThat(firstPage.get("sampleRows")).hasSize(20);
        assertThat(firstPage.get("stats").get("dataRows").asInt()).isEqualTo(30);

        JsonNode secondPage = api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode, "sampleLimit", 40)))
                .json().get("preview");
        assertThat(secondPage.get("sampleRows")).hasSize(30);
    }

    @Test
    void stopAtFirstErrorEndsTheImportAtTheBrokenRow() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = """
                Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
                STOP-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                STOP-%s-2,2026-01-03,2031-01-03,not-a-number,USD,0.04,SELL
                STOP-%s-3,2026-01-04,2031-01-04,3000,USD,0.05,BUY
                """.formatted(suffix, suffix, suffix);

        JsonNode stopped = importSheet(csv, "stop_" + suffix + ".csv", productCode,
                Map.of("stopAtFirstError", true));
        assertThat(stopped.get("totalRows").asInt()).isEqualTo(1);
        assertThat(stopped.get("invalidRows").asInt()).isZero();
        assertThat(stopped.get("issues").toString())
                .contains("IMPORT_STOPPED")
                .contains("TYPE_MISMATCH");

        JsonNode continued = importSheet(csv, "stop2_" + suffix + ".csv", productCode,
                Map.of("stopAtFirstError", false));
        assertThat(continued.get("totalRows").asInt()).isEqualTo(3);
        assertThat(continued.get("invalidRows").asInt()).isEqualTo(1);
    }

    @Test
    void stopAtEmptyRowEndsTheImportAtTheBlankLine() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = """
                Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
                BLANK-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                ,,,,,,
                BLANK-%s-2,2026-01-04,2031-01-04,3000,USD,0.05,BUY
                """.formatted(suffix, suffix);

        JsonNode stopped = importSheet(csv, "blank_" + suffix + ".csv", productCode,
                Map.of("stopAtEmptyRow", true));
        assertThat(stopped.get("totalRows").asInt()).isEqualTo(1);

        JsonNode continued = importSheet(csv, "blank2_" + suffix + ".csv", productCode,
                Map.of("stopAtEmptyRow", false));
        assertThat(continued.get("totalRows").asInt()).isEqualTo(2);
    }

    /** Uploads and imports the CSV with extra top level request flags, returning the import response. */
    private JsonNode importSheet(String csv, String fileName, String productCode, Map<String, Object> flags) {
        var upload = api.upload("/api/uploads", fileName, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        Map<String, Object> payload = new java.util.LinkedHashMap<>(flags);
        payload.put("uploadId", upload.json().get("uploadId").asString());
        payload.put("force", false);
        payload.put("sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)));
        var imported = api.post("/api/imports", api.toJson(payload));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json();
    }

    @Test
    void taskListFiltersByCreationDateAndExposesTheSourcePath() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = CSV_TEMPLATE.formatted("PATH-" + suffix, "PATH-" + suffix);
        var upload = api.upload("/api/uploads", "path_" + suffix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        String taskId = imported.json().get("taskId").asString();

        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        JsonNode todayPage = api.get("/api/tasks?q=" + taskId + "&from=" + today + "&to=" + today).json();
        assertThat(todayPage.get("total").asInt()).isEqualTo(1);
        JsonNode item = todayPage.get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(taskId);
        assertThat(item.get("sourcePath").asString())
                .contains("/uploads/").contains(upload.json().get("uploadId").asString()).endsWith(".csv");
        assertThat(item.get("sourceExists").asBoolean()).isTrue();
        assertThat(item.get("baseProducts").toString()).contains("\"IRS\"");
        assertThat(item.get("name").asString()).isNotEmpty();

        String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1).toString();
        assertThat(api.get("/api/tasks?q=" + taskId + "&from=" + tomorrow).json().get("total").asInt())
                .as("a future start date excludes the task").isZero();

        // the product filter works on base products, so any derivative of IRS matches
        assertThat(api.get("/api/tasks?q=" + taskId + "&product=IRS").json().get("total").asInt())
                .isEqualTo(1);
        assertThat(api.get("/api/tasks?q=" + taskId + "&product=OIS").json().get("total").asInt()).isZero();

        // the free text search also matches the task name
        String taskName = item.get("name").asString();
        assertThat(api.get("/api/tasks?q=" + java.net.URLEncoder.encode(taskName, StandardCharsets.UTF_8))
                .json().get("total").asInt()).as("the task name is searchable").isGreaterThanOrEqualTo(1);
    }

    @Test
    void taskNameIsUniqueAndCanBeRenamed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String first = importCsv("name_a_" + suffix + ".csv", CSV_TEMPLATE.formatted("N1-" + suffix, "N1-" + suffix),
                productCode);
        String second = importCsv("name_b_" + suffix + ".csv", CSV_TEMPLATE.formatted("N2-" + suffix, "N2-" + suffix),
                productCode);
        String firstName = taskItem(first).get("name").asString();
        String secondName = taskItem(second).get("name").asString();
        assertThat(firstName).isNotBlank();
        assertThat(secondName).as("default names stay unique").isNotEqualTo(firstName);

        String unique = "Renamed " + suffix;
        assertThat(api.patch("/api/tasks/" + first + "/name", api.toJson(Map.of("name", "  " + unique + "  "))).ok())
                .isTrue();
        assertThat(taskItem(first).get("name").asString()).isEqualTo(unique);

        var duplicate = api.patch("/api/tasks/" + second + "/name", api.toJson(Map.of("name", unique)));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.json().get("code").asString()).isEqualTo("CONFLICT");

        var blank = api.patch("/api/tasks/" + second + "/name", api.toJson(Map.of("name", "   ")));
        assertThat(blank.status()).isEqualTo(400);

        var tooLong = api.patch("/api/tasks/" + second + "/name",
                api.toJson(Map.of("name", "x".repeat(257))));
        assertThat(tooLong.status()).isEqualTo(400);
    }

    @Test
    void taskListSortsByNameBatchCountAndCreationTime() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toLowerCase();
        List<String> keep = List.of("testCaseId", "remarks", "tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "direction");
        String firstProduct = productWithFields("MW", "IRS", keep);
        String secondProduct = productWithFields("MW", "IRS", keep);
        String older = importCsv("sort_a_" + suffix + ".csv",
                CSV_TEMPLATE.formatted("SA-" + suffix, "SA-" + suffix), firstProduct);
        // created_at is stored per second, so keep the two tasks in different seconds
        Thread.sleep(1100);
        String newer = importCsvWithTwoBatches("sort_b_" + suffix + ".csv",
                CSV_TEMPLATE.formatted("SB-" + suffix, "SB-" + suffix), firstProduct, secondProduct);
        rename(older, "Sort-" + suffix + "-a");
        rename(newer, "Sort-" + suffix + "-z");
        String q = "Sort-" + suffix;

        assertThat(taskItem(newer).get("batchCount").asInt()).as("one batch per submitted sheet").isEqualTo(2);

        assertThat(taskIds("/api/tasks?q=" + q + "&sort=created_asc"))
                .as("ascending creation time").containsExactly(older, newer);
        assertThat(taskIds("/api/tasks?q=" + q + "&sort=created_desc")).containsExactly(newer, older);
        assertThat(taskIds("/api/tasks?q=" + q)).as("the default order is newest first")
                .containsExactly(newer, older);

        assertThat(taskIds("/api/tasks?q=" + q + "&sort=name_asc")).containsExactly(older, newer);
        assertThat(taskIds("/api/tasks?q=" + q + "&sort=name_desc")).containsExactly(newer, older);

        assertThat(taskIds("/api/tasks?q=" + q + "&sort=batches_asc")).containsExactly(older, newer);
        assertThat(taskIds("/api/tasks?q=" + q + "&sort=batches_desc")).containsExactly(newer, older);
    }

    private void rename(String taskId, String name) {
        var response = api.patch("/api/tasks/" + taskId + "/name", api.toJson(Map.of("name", name)));
        assertThat(response.ok()).as(response.body()).isTrue();
    }

    private List<String> taskIds(String path) {
        JsonNode page = api.get(path).json();
        List<String> ids = new java.util.ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private String importCsvWithTwoBatches(String fileName, String csv, String firstProduct,
            String secondProduct) {
        var upload = api.upload("/api/uploads", fileName, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", firstProduct),
                        Map.of("sheetIndex", 0, "productCode", secondProduct)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("taskId").asString();
    }

    private JsonNode taskItem(String taskId) {
        JsonNode page = api.get("/api/tasks?q=" + taskId).json();
        assertThat(page.get("total").asInt()).isEqualTo(1);
        return page.get("items").get(0);
    }

    private String importCsv(String fileName, String csv, String productCode) {
        var upload = api.upload("/api/uploads", fileName, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("taskId").asString();
    }

    @Test
    void invalidParamsReturnProblemDetail() {
        var response = api.post("/api/uploads/00000000-0000-0000-0000-000000000000/preview",
                api.toJson(Map.of("sheetIndex", 0)));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void deletingTaskCascades() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String csv = CSV_TEMPLATE.formatted("DEL-" + suffix, "DEL-" + suffix);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "direction"));
        var upload = api.upload("/api/uploads", "del_" + suffix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();
        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId,
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();
        String batchId = importResponse.json().get("batches").get(0).get("batchId").asString();
        String taskId = importResponse.json().get("taskId").asString();

        var delete = api.delete("/api/tasks/" + taskId);
        assertThat(delete.ok()).as(delete.body()).isTrue();
        assertThat(api.get("/api/tasks/" + taskId).status()).isEqualTo(404);
        assertThat(api.get("/api/batches/" + batchId).status()).isEqualTo(404);
    }
}
