package com.transactionprocessor.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;
import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.model.GoogleSheetsRow;
import com.transactionprocessor.model.ProcessingResult;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;

public class TransactionProcessorBackupTest {

    static class FakeSheets extends GoogleSheetsService {
        List<String> events = new ArrayList<>();
        java.util.List<GoogleSheetsRow> rows = new java.util.ArrayList<>();

        public FakeSheets() {
            super((com.transactionprocessor.config.GoogleSheetsConfig)null, null);
        }

        @Override
        public java.util.List<GoogleSheetsRow> readSheet(String spreadsheetId, String sheetName) {
            return new java.util.ArrayList<>(rows);
        }

        @Override
        public int duplicateSheet(String spreadsheetId, String sourceSheetName, String newSheetName, Integer insertSheetIndex) {
            events.add("duplicate");
            return 9999;
        }

        @Override
        public void writeRows(String spreadsheetId, String sheetName, java.util.List<GoogleSheetsRow> rows, int startRow) {
            events.add("write");
        }

        @Override
        public void applyFormatting(String spreadsheetId, String sheetName, java.util.List<FormatRequest> formatRequests) {
            events.add("format");
        }
    }

    @Test
    public void backupIsCreatedBeforeWrite() throws Exception {
        ApplicationConfig cfg = new ApplicationConfig(ConfigFactory.parseString("app.dry.run=false").withFallback(ConfigFactory.load()));
        FakeSheets fake = new FakeSheets();
        GoogleSheetsRow row = new GoogleSheetsRow("TST", "D", LocalDate.now(), new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0"), "desc", "R");
        fake.rows.add(row);

        com.transactionprocessor.service.CodeLabelMappingService mappingService = new com.transactionprocessor.service.CodeLabelMappingService((java.nio.file.Path)null);
        TransactionProcessor tp = new TransactionProcessor(cfg, fake, mappingService);

        Transaction tx = new Transaction("Test", "CODE", TransactionType.BUY, LocalDate.now(), new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("10"), "", "");
        ProcessingResult result = tp.processTransactions("dummy-spreadsheet", "Sheet1", List.of(tx));

        assertTrue(fake.events.size() >= 1, "Some events should have been recorded");
        // duplicate should be first
        assertEquals("duplicate", fake.events.get(0), "duplicateSheet should be invoked before writes");
        // verify backup name recorded in result
        assertNotNull(result.getBackupSheetName(), "ProcessingResult should contain backup sheet name");
    }
}
