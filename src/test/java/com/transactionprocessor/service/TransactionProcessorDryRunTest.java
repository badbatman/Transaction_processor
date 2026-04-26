package com.transactionprocessor.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;
import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.model.GoogleSheetsRow;
import com.transactionprocessor.model.ProcessingResult;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;

public class TransactionProcessorDryRunTest {

    static class FakeSheets extends GoogleSheetsService {
        boolean writeCalled = false;
        boolean formatCalled = false;
        boolean duplicateCalled = false;
        java.util.List<GoogleSheetsRow> rows = new java.util.ArrayList<>();

        public FakeSheets() {
            super((com.transactionprocessor.config.GoogleSheetsConfig)null, null);
        }

        @Override
        public java.util.List<GoogleSheetsRow> readSheet(String spreadsheetId, String sheetName) {
            return new java.util.ArrayList<>(rows);
        }

        @Override
        public void writeRows(String spreadsheetId, String sheetName, java.util.List<GoogleSheetsRow> rows, int startRow) {
            this.writeCalled = true;
        }

        @Override
        public void applyFormatting(String spreadsheetId, String sheetName, java.util.List<FormatRequest> formatRequests) {
            this.formatCalled = true;
        }

        @Override
        public int duplicateSheet(String spreadsheetId, String sourceSheetName, String newSheetName, Integer insertSheetIndex) {
            this.duplicateCalled = true;
            return -1;
        }
    }

    @Test
    public void dryRunWritesPreviewAndNoRemoteCalls() throws Exception {
        ApplicationConfig cfg = new ApplicationConfig(ConfigFactory.parseString("app.dry.run=true").withFallback(ConfigFactory.load()));
        FakeSheets fake = new FakeSheets();
        // Provide one existing row so processor will 'write' something in preview
        GoogleSheetsRow row = new GoogleSheetsRow("TST", "D", LocalDate.now(), new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0"), "desc", "R");
        fake.rows.add(row);

        com.transactionprocessor.service.CodeLabelMappingService mappingService = new com.transactionprocessor.service.CodeLabelMappingService((java.nio.file.Path)null);
        TransactionProcessor tp = new TransactionProcessor(cfg, fake, mappingService);

        Transaction tx = new Transaction("Test", "CODE", TransactionType.BUY, LocalDate.now(), new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("10"), "", "");
        ProcessingResult result = tp.processTransactions("dummy-spreadsheet", "Sheet1", List.of(tx));

        assertTrue(result.isDryRun(), "Result should indicate dry run");
        assertFalse(fake.writeCalled, "writeRows should NOT be called during dry-run");
        assertFalse(fake.formatCalled, "applyFormatting should NOT be called during dry-run");
        assertFalse(fake.duplicateCalled, "duplicateSheet should NOT be called during dry-run");

        // Check preview file exists under target/
        Path targetDir = Path.of("target");
        boolean found = Files.exists(targetDir) && Files.list(targetDir).anyMatch(p -> p.getFileName().toString().startsWith("preview-Sheet1-") && p.getFileName().toString().endsWith(".json"));
        assertTrue(found, "Preview JSON should be written under target/");
    }
}
