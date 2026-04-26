package com.transactionprocessor.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Unit tests for DividendTransactionProcessor
 */
class DividendTransactionProcessorTest {
    
    private DividendTransactionProcessor dividendProcessor;
    
    @BeforeEach
    void setUp() {
        // Use null GoogleSheetsService since we're not actually calling Google Sheets APIs in these tests
        dividendProcessor = new DividendTransactionProcessor(null);
    }
    
    @Test
    void testProcessTransaction_NewDividendRow() throws Exception {
        // Given
        Transaction dividendTransaction = createTestTransaction("DIV_STOCK", "DIV001", TransactionType.DIVIDEND);
        dividendTransaction.setDescription("每10股股息2.5元");
        dividendTransaction.setAmount(new BigDecimal("250.00")); // Total dividend amount

        List<GoogleSheetsRow> existingRows = new ArrayList<>();

        // When
        dividendProcessor.processTransaction(dividendTransaction, existingRows, "spreadsheetId", "sheetName");

        // Then
        assertThat(existingRows).hasSize(1);
        GoogleSheetsRow dividendRow = existingRows.get(0);
        assertThat(dividendRow.getLabel()).isEqualTo("DIV_STOCK");
        assertThat(dividendRow.getOpenTime()).isEqualTo(dividendTransaction.getDate());
        assertThat(dividendRow.getOpenPrice()).isEqualTo(BigDecimal.ZERO);
        // REQUIREMENT.md Lines 110-112: 原始股数量 = 1
        assertThat(dividendRow.getNumberOfStock()).isEqualTo(new BigDecimal("1"));
        assertThat(dividendRow.getOpenFeeTax()).isEqualTo(BigDecimal.ZERO);
        assertThat(dividendRow.getCloseTime()).isEqualTo(dividendTransaction.getDate());
        // REQUIREMENT.md Lines 110-112: 每股股息 = 派息總金額
        assertThat(dividendRow.getClosePrice()).isEqualByComparingTo("250.00");
        // REQUIREMENT.md Line 119: I列（Fee + Tax）：每次派息的稅費
        assertThat(dividendRow.getCloseFeeTax()).isEqualTo(BigDecimal.ZERO);
        assertThat(dividendRow.getDescription()).isEqualTo("派息");
        assertThat(dividendRow.isStrikethrough()).isTrue();
        assertThat(dividendRow.isBold()).isTrue();
        assertThat(dividendRow.isItalic()).isTrue();
        assertThat(dividendRow.isValid()).isFalse();
    }
    
    @Test
    void testProcessTransaction_AddAfterExistingRow() throws Exception {
        // Given
        Transaction dividendTransaction = createTestTransaction("EXIST_DIV", "EXIST001", TransactionType.DIVIDEND);
        dividendTransaction.setDescription("每10股股息1.8元");
        dividendTransaction.setAmount(new BigDecimal("180.00")); // Total dividend amount

        GoogleSheetsRow existingRow = new GoogleSheetsRow();
        existingRow.setLabel("EXIST_DIV");
        existingRow.setNumberOfStock(new BigDecimal("500"));
        existingRow.setValid(true);
        existingRow.setRowNumber(1); // Must set rowNumber to avoid NullPointerException
        existingRow.setFormulaColumns(List.of("=J1", "=K1", "", "", "", "", "", ""));

        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        existingRows.add(existingRow);

        // When
        dividendProcessor.processTransaction(dividendTransaction, existingRows, "spreadsheetId", "sheetName");

        // Then
        assertThat(existingRows).hasSize(2);
        GoogleSheetsRow dividendRow = existingRows.get(1);
        assertThat(dividendRow.getLabel()).isEqualTo("EXIST_DIV");
        // REQUIREMENT.md Lines 110-112: 原始股数量 = 1
        assertThat(dividendRow.getNumberOfStock()).isEqualTo(new BigDecimal("1"));
        assertThat(dividendRow.getFormulaColumns()).containsExactly("=J1", "=K1", "", "", "", "", "", "");
        assertThat(dividendRow.getDescription()).isEqualTo("派息");
        assertThat(dividendRow.isStrikethrough()).isTrue();
    }
    
    @Test
    void testProcessTransaction_CopyToEmptyRow() throws Exception {
        // Given
        Transaction dividendTransaction = createTestTransaction("EMPTY_DIV", "EMPTY001", TransactionType.DIVIDEND);
        dividendTransaction.setDescription("每10股股息3.0元");
        dividendTransaction.setAmount(new BigDecimal("300.00")); // Total dividend amount

        GoogleSheetsRow existingRow = new GoogleSheetsRow();
        existingRow.setLabel("EMPTY_DIV");
        existingRow.setNumberOfStock(new BigDecimal("200"));
        existingRow.setValid(true);
        existingRow.setRowNumber(1); // Must set rowNumber to avoid NullPointerException

        GoogleSheetsRow emptyRow = new GoogleSheetsRow();
        emptyRow.setRowNumber(2); // Must set rowNumber for the empty row

        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        existingRows.add(existingRow);
        existingRows.add(emptyRow);

        // When
        dividendProcessor.processTransaction(dividendTransaction, existingRows, "spreadsheetId", "sheetName");

        // Then
        assertThat(existingRows).hasSize(2);
        GoogleSheetsRow updatedRow = existingRows.get(1);
        assertThat(updatedRow.getLabel()).isEqualTo("EMPTY_DIV");
        // REQUIREMENT.md Lines 110-112: 原始股数量 = 1
        assertThat(updatedRow.getNumberOfStock()).isEqualTo(new BigDecimal("1"));
        assertThat(updatedRow.getDescription()).isEqualTo("派息");
    }
    
    @Test
    void testProcessTransaction_NoDescription_ThrowsException() {
        // Given
        Transaction dividendTransaction = createTestTransaction("NO_DESC", "NODESC001", TransactionType.DIVIDEND);
        dividendTransaction.setDescription(null); // No description
        
        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        
        // When & Then
        assertThatThrownBy(() -> dividendProcessor.processTransaction(dividendTransaction, existingRows, "spreadsheetId", "sheetName"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unable to parse dividend information");
    }
    
    @Test
    void testProcessTransaction_InvalidDividendPattern_ThrowsException() {
        // Given
        Transaction dividendTransaction = createTestTransaction("INVALID_PATTERN", "INVALID001", TransactionType.DIVIDEND);
        dividendTransaction.setDescription("Some random description without dividend info");
        
        List<GoogleSheetsRow> existingRows = new ArrayList<>();
        
        // When & Then
        assertThatThrownBy(() -> dividendProcessor.processTransaction(dividendTransaction, existingRows, "spreadsheetId", "sheetName"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unable to parse dividend information");
    }
    
    @Test
    void testParseDividendInfo_ExactFormula() throws Exception {
        // Test the dividend parsing using the NEW simplified requirement (lines 110-112)
        // Original shares = 1 (always), dividendPerShare = transaction amount
        try {
            Transaction dividendTransaction = createTestTransaction("FORMULA_TEST", "FORM001", TransactionType.DIVIDEND);
            dividendTransaction.setDescription("每10股股息2.5元");
            dividendTransaction.setAmount(new BigDecimal("250.00"));

            // Use reflection to test the private method
            java.lang.reflect.Method method = DividendTransactionProcessor.class.getDeclaredMethod(
                "parseDividendInfo", Transaction.class);
            method.setAccessible(true);

            Object dividendInfo = method.invoke(dividendProcessor, dividendTransaction);

            // Access the fields using reflection
            java.lang.reflect.Field originalSharesField = dividendInfo.getClass().getDeclaredField("originalShares");
            originalSharesField.setAccessible(true);
            BigDecimal originalShares = (BigDecimal) originalSharesField.get(dividendInfo);

            java.lang.reflect.Field dividendPerShareField = dividendInfo.getClass().getDeclaredField("dividendPerShare");
            dividendPerShareField.setAccessible(true);
            BigDecimal dividendPerShare = (BigDecimal) dividendPerShareField.get(dividendInfo);

            // REQUIREMENT.md Lines 110-112: NEW calculation
            // 原始股数量 = 1
            assertThat(originalShares).isEqualByComparingTo(new BigDecimal("1"));
            // 每股股息 = 派息總金額
            assertThat(dividendPerShare).isEqualByComparingTo(new BigDecimal("250.00"));

        } catch (Exception e) {
            throw new RuntimeException("Failed to test parseDividendInfo method", e);
        }
    }
    
    @Test
    void testParseDividendInfo_Rounding() throws Exception {
        // Test the NEW simplified requirement - no rounding needed since originalShares = 1 always
        try {
            Transaction dividendTransaction = createTestTransaction("ROUNDING_TEST", "ROUND001", TransactionType.DIVIDEND);
            dividendTransaction.setDescription("每10股股息3.33元");
            dividendTransaction.setAmount(new BigDecimal("100.00"));

            java.lang.reflect.Method method = DividendTransactionProcessor.class.getDeclaredMethod(
                "parseDividendInfo", Transaction.class);
            method.setAccessible(true);

            Object dividendInfo = method.invoke(dividendProcessor, dividendTransaction);

            java.lang.reflect.Field originalSharesField = dividendInfo.getClass().getDeclaredField("originalShares");
            originalSharesField.setAccessible(true);
            BigDecimal originalShares = (BigDecimal) originalSharesField.get(dividendInfo);

            java.lang.reflect.Field dividendPerShareField = dividendInfo.getClass().getDeclaredField("dividendPerShare");
            dividendPerShareField.setAccessible(true);
            BigDecimal dividendPerShare = (BigDecimal) dividendPerShareField.get(dividendInfo);

            // REQUIREMENT.md Lines 110-112: NEW calculation - no rounding needed
            // 原始股数量 = 1 (always)
            assertThat(originalShares).isEqualByComparingTo(new BigDecimal("1"));
            // 每股股息 = 派息總金額
            assertThat(dividendPerShare).isEqualByComparingTo(new BigDecimal("100.00"));

        } catch (Exception e) {
            throw new RuntimeException("Failed to test rounding behavior", e);
        }
    }
    
    @Test
    void testDetermineRegion_VariousCodes() {
        // Test region determination
        try {
            java.lang.reflect.Method method = DividendTransactionProcessor.class.getDeclaredMethod(
                "determineRegion", String.class);
            method.setAccessible(true);
            
            assertThat(method.invoke(dividendProcessor, "SH600001")).isEqualTo("A股");
            assertThat(method.invoke(dividendProcessor, "SZ000001")).isEqualTo("A股");
            assertThat(method.invoke(dividendProcessor, "HKG:0001")).isEqualTo("港股");
            assertThat(method.invoke(dividendProcessor, "AAPL")).isEqualTo("美股");
            assertThat(method.invoke(dividendProcessor, "MSFT")).isEqualTo("美股");
            
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
        transaction.setPrice(BigDecimal.ZERO); // Dividends typically have 0 price
        transaction.setQuantity(BigDecimal.ONE); // Not really used for dividends
        transaction.setAmount(new BigDecimal("100.00"));
        return transaction;
    }
}