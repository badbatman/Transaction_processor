package com.transactionprocessor.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.transactionprocessor.model.GoogleSheetsRow;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;
import com.transactionprocessor.service.GoogleSheetsService;

/**
 * Unit tests for Sell and Dividend processors using a lightweight FakeSheets.
 */
class SellDividendProcessorsTest {

    @Test
    void sellExactClosesPosition() throws Exception {
        // Prepare a single open position
        List<GoogleSheetsRow> rows = new ArrayList<>();
        GoogleSheetsRow position = new GoogleSheetsRow();
        position.setLabel("TEST_LABEL");
        position.setNumberOfStock(new BigDecimal("10"));
        position.setOpenPrice(new BigDecimal("1.00"));
        position.setValid(true);
        rows.add(position);

        // Create sell transaction that exactly matches the position
        Transaction sell = new Transaction();
        sell.setName("TEST_LABEL");
        sell.setCode("CODE1");
        sell.setType(TransactionType.SELL);
        sell.setDate(LocalDate.of(2025, 11, 18));
        sell.setPrice(new BigDecimal("3.00"));
        sell.setQuantity(new BigDecimal("10"));
        sell.setAmount(new BigDecimal("30.00"));

        // Use a fake sheets service (not used directly by processor logic here)
        FakeSheets fakeSheets = new FakeSheets();

        SellTransactionProcessor processor = new SellTransactionProcessor(fakeSheets);
        processor.processTransaction(sell, rows, "sid", "Sheet1");

        // After processing, the single position should be closed
        assertThat(rows).hasSize(1);
        GoogleSheetsRow updated = rows.get(0);
        assertThat(updated.isStrikethrough()).isTrue();
        assertThat(updated.isBold()).isTrue();
        assertThat(updated.isItalic()).isTrue();
        assertThat(updated.isValid()).isFalse();
        assertThat(updated.getClosePrice()).isEqualByComparingTo(new BigDecimal("3.00"));
    }

    @Test
    void dividendCreatesClosedRow() throws Exception {
        // Start with no existing rows
        List<GoogleSheetsRow> rows = new ArrayList<>();

        // Dividend: amount = 5.00
        // REQUIREMENT.md Lines 110-112: NEW simplified calculation
        // - 原始股数量 = 1 (always)
        // - 每股股息 = 派息總金額 = 5.00
        Transaction div = new Transaction();
        div.setName("TEST_LABEL");
        div.setCode("CODE1");
        div.setType(TransactionType.DIVIDEND);
        div.setDate(LocalDate.of(2025, 11, 18));
        div.setPrice(BigDecimal.ZERO);
        div.setQuantity(BigDecimal.ZERO);
        div.setAmount(new BigDecimal("5.00"));
        div.setDescription("每10股股息0.50");

        FakeSheets fakeSheets = new FakeSheets();
        DividendTransactionProcessor processor = new DividendTransactionProcessor(fakeSheets);
        processor.processTransaction(div, rows, "sid", "Sheet1");

        // Should have created one dividend row marked closed
        assertThat(rows).hasSize(1);
        GoogleSheetsRow row = rows.get(0);
        assertThat(row.isValid()).isFalse();
        assertThat(row.isStrikethrough()).isTrue();
        // REQUIREMENT.md Lines 110-112: numberOfStock = 1 always
        assertThat(row.getNumberOfStock()).isEqualByComparingTo(new BigDecimal("1"));
        // REQUIREMENT.md Lines 110-112: closePrice = transaction amount
        assertThat(row.getClosePrice()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    // Minimal fake GoogleSheetsService used for processor unit tests
    private static class FakeSheets extends GoogleSheetsService {
        public FakeSheets() {
            super((com.transactionprocessor.config.GoogleSheetsConfig) null, null);
        }

        @Override
        public java.util.List<com.transactionprocessor.model.GoogleSheetsRow> readSheet(String spreadsheetId, String sheetName) {
            return new ArrayList<>();
        }
    }
}
