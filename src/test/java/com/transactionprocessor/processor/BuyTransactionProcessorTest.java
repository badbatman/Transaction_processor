package com.transactionprocessor.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.transactionprocessor.model.GoogleSheetsRow;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;

/**
 * Unit tests for BuyTransactionProcessor
 */
class BuyTransactionProcessorTest {
    
    private BuyTransactionProcessor buyProcessor;
    
    @BeforeEach
    void setUp() {
        // Use null GoogleSheetsService since we're not actually calling Google Sheets APIs in these tests
        buyProcessor = new BuyTransactionProcessor(null);
    }
    
    @Test
    void testProcessTransaction_NewBuyRow() throws Exception {
        // Given
        Transaction transaction = createTestTransaction("NEW_STOCK", "NEW001", TransactionType.BUY);
        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        
        // When
        buyProcessor.processTransaction(transaction, existingRows, "spreadsheetId", "sheetName");
        
        // Then
        assertThat(existingRows).hasSize(1);
        GoogleSheetsRow newRow = existingRows.get(0);
        assertThat(newRow.getLabel()).isEqualTo("NEW_STOCK");
        assertThat(newRow.getOpenTime()).isEqualTo(transaction.getDate());
        assertThat(newRow.getOpenPrice()).isEqualTo(transaction.getPrice());
        assertThat(newRow.getNumberOfStock()).isEqualTo(transaction.getQuantity());
        assertThat(newRow.getOpenFeeTax()).isEqualTo(transaction.calculateFee());
        assertThat(newRow.isBold()).isTrue();
        assertThat(newRow.isItalic()).isTrue();
        assertThat(newRow.isStrikethrough()).isFalse();
        assertThat(newRow.isValid()).isTrue();
    }
    
    @Test
    void testProcessTransaction_AddAfterExistingRow() throws Exception {
        // Given
        Transaction transaction = createTestTransaction("EXISTING_STOCK", "EXIST001", TransactionType.BUY);

        GoogleSheetsRow existingRow = new GoogleSheetsRow();
        existingRow.setLabel("EXISTING_STOCK");
        existingRow.setNumberOfStock(new BigDecimal("100"));
        existingRow.setValid(true);
        existingRow.setRowNumber(1); // Must set rowNumber to avoid NullPointerException
        existingRow.setFormulaColumns(List.of("=J1", "=K1", "", "", "", "", "", ""));
        
        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        existingRows.add(existingRow);
        
        // When
        buyProcessor.processTransaction(transaction, existingRows, "spreadsheetId", "sheetName");
        
        // Then
        assertThat(existingRows).hasSize(2);
        GoogleSheetsRow newRow = existingRows.get(1);
        assertThat(newRow.getLabel()).isEqualTo("EXISTING_STOCK");
        assertThat(newRow.getNumberOfStock()).isEqualTo(transaction.getQuantity());
        assertThat(newRow.getFormulaColumns()).containsExactly("=J1", "=K1", "", "", "", "", "", "");
        assertThat(newRow.isBold()).isTrue();
        assertThat(newRow.isItalic()).isTrue();
    }
    
    @Test
    void testProcessTransaction_CopyToEmptyRow() throws Exception {
        // Given
        Transaction transaction = createTestTransaction("STOCK_WITH_EMPTY", "EMPTY001", TransactionType.BUY);

        GoogleSheetsRow existingRow = new GoogleSheetsRow();
        existingRow.setLabel("STOCK_WITH_EMPTY");
        existingRow.setNumberOfStock(new BigDecimal("50"));
        existingRow.setValid(true);
        existingRow.setRowNumber(1); // Must set rowNumber to avoid NullPointerException

        GoogleSheetsRow emptyRow = new GoogleSheetsRow(); // Empty row
        emptyRow.setRowNumber(2); // Must set rowNumber for the empty row
        
        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        existingRows.add(existingRow);
        existingRows.add(emptyRow);
        
        // When
        buyProcessor.processTransaction(transaction, existingRows, "spreadsheetId", "sheetName");
        
        // Then
        assertThat(existingRows).hasSize(2);
        GoogleSheetsRow updatedRow = existingRows.get(1);
        assertThat(updatedRow.getLabel()).isEqualTo("STOCK_WITH_EMPTY");
        assertThat(updatedRow.getNumberOfStock()).isEqualTo(transaction.getQuantity());
        assertThat(updatedRow.isBold()).isTrue();
        assertThat(updatedRow.isItalic()).isTrue();
    }
    
    @Test
    void testProcessTransaction_PreserveDescriptionWithRemarks() throws Exception {
        // Given
        Transaction transaction = createTestTransaction("DESC_STOCK", "DESC001", TransactionType.BUY);
        transaction.setRemarks("Additional remark");

        GoogleSheetsRow existingRow = new GoogleSheetsRow();
        existingRow.setLabel("DESC_STOCK");
        existingRow.setDescription("Original description");
        existingRow.setValid(true);
        existingRow.setRowNumber(1); // Must set rowNumber to avoid NullPointerException
        
        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        existingRows.add(existingRow);
        
        // When
        buyProcessor.processTransaction(transaction, existingRows, "spreadsheetId", "sheetName");
        
        // Then
        GoogleSheetsRow newRow = existingRows.get(1);
        // Description should use ONLY transaction remarks, not combine with template description
        assertThat(newRow.getDescription()).isEqualTo("Additional remark");
    }
    
    @Test
    void testProcessTransaction_SetRegionBasedOnCode() throws Exception {
        // Given
        Transaction transaction = createTestTransaction("REGION_TEST", "SH600001", TransactionType.BUY);
        
        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        
        // When
        buyProcessor.processTransaction(transaction, existingRows, "spreadsheetId", "sheetName");
        
        // Then
        GoogleSheetsRow newRow = existingRows.get(0);
        assertThat(newRow.getRegion()).isEqualTo("A股");
    }
    
    @Test
    void testDetermineRegion_VariousCodes() {
        // Test reflection to access private method
        try {
            java.lang.reflect.Method method = BuyTransactionProcessor.class.getDeclaredMethod(
                "determineRegion", String.class);
            method.setAccessible(true);
            
            assertThat(method.invoke(buyProcessor, "SH600001")).isEqualTo("A股");
            assertThat(method.invoke(buyProcessor, "SZ000001")).isEqualTo("A股");
            assertThat(method.invoke(buyProcessor, "HKG:0001")).isEqualTo("港股");
            assertThat(method.invoke(buyProcessor, "AAPL")).isEqualTo("美股");
            assertThat(method.invoke(buyProcessor, "MSFT")).isEqualTo("美股");
            assertThat(method.invoke(buyProcessor, "UNKNOWN")).isEqualTo("其他");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to test determineRegion method", e);
        }
    }
    
    private Transaction createTestTransaction(String name, String code, TransactionType type) {
        Transaction transaction = new Transaction();
        transaction.setName(name);
        transaction.setCode(code);
        transaction.setType(type);
        transaction.setDate(LocalDate.of(2025, 4, 1));
        transaction.setPrice(new BigDecimal("100.00"));
        transaction.setQuantity(new BigDecimal("10"));
        transaction.setAmount(new BigDecimal("1000.00"));
        return transaction;
    }
}