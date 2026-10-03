package com.transactionprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;

/**
 * Unit tests for CsvParserService
 */
class CsvParserServiceTest {

    private CsvParserService csvParserService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        csvParserService = new CsvParserService();
    }

    @Test
    void testParseValidCsvFile() throws IOException {
        // Create a test CSV file
        Path csvFile = tempDir.resolve("test_transactions.csv");
        String csvContent = "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n" +
            "中证A500ETF富国,SH563220,买入,2025/04/28,1.00,1000,1000.00,,\n" +
            "中证A500ETF富国,SH563220,卖出,2025/04/29,1.05,500,525.00,,\n" +
            "阿里巴巴-W,09988,除权除息,2025/04/30,0.00,0,50.00,每10股股息5.0,\n";
        
        Files.writeString(csvFile, csvContent);

        // Parse the CSV file
        List<Transaction> transactions = csvParserService.parseCsvFile(csvFile);

        // Verify results
        assertThat(transactions).hasSize(3);
        
        // Verify buy transaction
        Transaction buyTransaction = transactions.get(0);
        assertThat(buyTransaction.getName()).isEqualTo("中证A500ETF富国");
        assertThat(buyTransaction.getCode()).isEqualTo("SH563220");
        assertThat(buyTransaction.getType()).isEqualTo(TransactionType.BUY);
        assertThat(buyTransaction.getDate()).isEqualTo(LocalDate.of(2025, 4, 28));
        assertThat(buyTransaction.getPrice()).isEqualTo("1.00");
        assertThat(buyTransaction.getQuantity()).isEqualTo("1000");
        assertThat(buyTransaction.getAmount()).isEqualTo("1000.00");
        
        // Verify sell transaction
        Transaction sellTransaction = transactions.get(1);
        assertThat(sellTransaction.getType()).isEqualTo(TransactionType.SELL);
        assertThat(sellTransaction.getQuantity()).isEqualTo("500");
        
        // Verify dividend transaction
        Transaction dividendTransaction = transactions.get(2);
        assertThat(dividendTransaction.getType()).isEqualTo(TransactionType.DIVIDEND);
        assertThat(dividendTransaction.getPrice()).isEqualTo("0.00");
        assertThat(dividendTransaction.getDescription()).contains("每10股股息5.0");
    }

    @Test
    void testParseGb18030CsvFile() throws IOException {
        Path csvFile = tempDir.resolve("TR_202610.csv");
        String csvContent = "雪球持仓组合：资产信息\n"
            + "交易记录\n"
            + "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n"
            + "科创50ETF华夏,SH588000,买入,2026/09/03,1.70,10000.0,16990.00,,\n";
        byte[] gb18030Content = csvContent.getBytes(Charset.forName("GB18030"));
        Files.write(csvFile, gb18030Content);

        List<Transaction> transactions = csvParserService.parseCsvFile(csvFile);
        List<Transaction> streamTransactions = csvParserService.parseCsvFile(
            new ByteArrayInputStream(gb18030Content));

        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).getName()).isEqualTo("科创50ETF华夏");
        assertThat(transactions.get(0).getType()).isEqualTo(TransactionType.BUY);
        assertThat(streamTransactions).hasSize(1);
        assertThat(streamTransactions.get(0).getName()).isEqualTo("科创50ETF华夏");
    }

    @Test
    void testFilterByDateRange() throws IOException {
        // Create test transactions with different dates
        Path csvFile = tempDir.resolve("date_range_test.csv");
        String csvContent = "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n" +
            "股票A,CODE1,买入,2025/03/15,10.00,100,1000.00,,\n" +
            "股票A,CODE1,买入,2025/04/01,11.00,100,1100.00,,\n" +
            "股票A,CODE1,买入,2025/04/15,12.00,100,1200.00,,\n" +
            "股票A,CODE1,买入,2025/05/01,13.00,100,1300.00,,\n";
        
        Files.writeString(csvFile, csvContent);
        List<Transaction> allTransactions = csvParserService.parseCsvFile(csvFile);

        // Filter by April 2025
        YearMonth april2025 = YearMonth.of(2025, 4);
        LocalDate startDate = april2025.atDay(1);
        LocalDate endDate = april2025.atEndOfMonth();
        
        List<Transaction> filteredTransactions = csvParserService.filterByDateRange(
            allTransactions, startDate, endDate);

        // Verify only April transactions are included
        assertThat(filteredTransactions).hasSize(2);
        assertThat(filteredTransactions.get(0).getDate()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(filteredTransactions.get(1).getDate()).isEqualTo(LocalDate.of(2025, 4, 15));
    }

    @Test
    void testValidateTransaction() {
        // Valid buy transaction
        Transaction validBuy = new Transaction();
        validBuy.setName("Test Stock");
        validBuy.setCode("TEST");
        validBuy.setType(TransactionType.BUY);
        validBuy.setDate(LocalDate.now());
        validBuy.setPrice(new BigDecimal("10.00"));
        validBuy.setQuantity(new BigDecimal("100"));
        validBuy.setAmount(new BigDecimal("1000.00"));
        
        assertThat(csvParserService.validateTransaction(validBuy)).isTrue();
        
        // Invalid transaction - missing required fields
        Transaction invalidTransaction = new Transaction();
        invalidTransaction.setName(""); // Empty name
        invalidTransaction.setCode("TEST");
        invalidTransaction.setType(TransactionType.BUY);
        // Missing other required fields
        
        assertThat(csvParserService.validateTransaction(invalidTransaction)).isFalse();
    }

    @Test
    void testCalculateFee() {
        Transaction transaction = new Transaction();
        transaction.setPrice(new BigDecimal("10.00"));
        transaction.setQuantity(new BigDecimal("100"));
        transaction.setAmount(new BigDecimal("1005.00")); // Includes 5.00 fee
        transaction.setRemarks("fee=5.00"); // Fee is specified in remarks field

        BigDecimal fee = transaction.calculateFee();

        assertThat(fee).isEqualByComparingTo("5.00");
    }

    @Test
    void testCalculateFeeFromDescriptionWhenFeeIsSplitAcrossText() {
        Transaction transaction = new Transaction();
        transaction.setDescription("卖出手续费 fee=1.71");
        transaction.setRemarks("交易说明");

        assertThat(transaction.calculateFee()).isEqualByComparingTo("1.71");

        transaction.setDescription("Sell Call OCTfee=9.23Sell Call OCT");
        transaction.setRemarks("");
        assertThat(transaction.calculateFee()).isEqualByComparingTo("9.23");
    }

    @Test
    void testParseStandaloneFeeContinuationIntoPreviousTransaction() throws IOException {
        Path csvFile = tempDir.resolve("fee_continuation.csv");
        Files.writeString(csvFile, "交易记录\n"
            + "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n"
            + "股票A,AAA,除权除息,2025/12/24,0,0,15.41,每10股股息3.42,\n"
            + "fee=1.71\n");

        List<Transaction> transactions = csvParserService.parseCsvFile(csvFile);

        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).calculateFee()).isEqualByComparingTo("1.71");
    }

    @Test
    void testDeriveDividendAmountFromPerTenSharesDescription() throws IOException {
        Path csvFile = tempDir.resolve("derived_dividend_amount.csv");
        Files.writeString(csvFile, "交易记录\n"
            + "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n"
            + "股票A,AAA,除权除息,2025/12/24,0,100,,每10股股息5.0,\n");

        List<Transaction> transactions = csvParserService.parseCsvFile(csvFile);

        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).getAmount()).isEqualByComparingTo("50");
    }

    @Test
    void testParseCsvWithCurrencySymbols() throws IOException {
        // Test parsing with currency symbols
        Path csvFile = tempDir.resolve("currency_test.csv");
        String csvContent = "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n" +
            "港股股票,HKG:1234,买入,2025/04/28,HK$10.50,1000,\"HK$10,500.00\",,\n" +
            "美股股票,USSTOCK,买入,2025/04/28,$50.25,100,\"$5,025.00\",,\n" +
            "A股股票,SH123456,买入,2025/04/28,¥15.75,500,\"¥7,875.00\",,\n";
        
        Files.writeString(csvFile, csvContent);
        List<Transaction> transactions = csvParserService.parseCsvFile(csvFile);

        assertThat(transactions).hasSize(3);
        
        // Verify currency symbols are properly removed
        assertThat(transactions.get(0).getPrice()).isEqualByComparingTo("10.50");
        assertThat(transactions.get(0).getAmount()).isEqualByComparingTo("10500.00");
        
        assertThat(transactions.get(1).getPrice()).isEqualByComparingTo("50.25");
        assertThat(transactions.get(1).getAmount()).isEqualByComparingTo("5025.00");
        
        assertThat(transactions.get(2).getPrice()).isEqualByComparingTo("15.75");
        assertThat(transactions.get(2).getAmount()).isEqualByComparingTo("7875.00");
    }

    @Test
    void testParseCsvWithDifferentDateFormats() throws IOException {
        // Test different date formats
        Path csvFile = tempDir.resolve("date_format_test.csv");
        String csvContent = "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n" +
            "股票A,CODE1,买入,2025/4/1,10.00,100,1000.00,,\n" +
            "股票B,CODE2,买入,2025-04-02,11.00,100,1100.00,,\n" +
            "股票C,CODE3,买入,2025/04/03,12.00,100,1200.00,,\n" +
            "股票D,CODE4,买入,2025-4-4,13.00,100,1300.00,,\n";
        
        Files.writeString(csvFile, csvContent);
        List<Transaction> transactions = csvParserService.parseCsvFile(csvFile);

        assertThat(transactions).hasSize(4);
        
        // Verify all dates are parsed correctly
        assertThat(transactions.get(0).getDate()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(transactions.get(1).getDate()).isEqualTo(LocalDate.of(2025, 4, 2));
        assertThat(transactions.get(2).getDate()).isEqualTo(LocalDate.of(2025, 4, 3));
        assertThat(transactions.get(3).getDate()).isEqualTo(LocalDate.of(2025, 4, 4));
    }

    @Test
    void testParseEmptyCsvFile() throws IOException {
        Path csvFile = tempDir.resolve("empty_test.csv");
        String csvContent = "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n";
        
        Files.writeString(csvFile, csvContent);
        List<Transaction> transactions = csvParserService.parseCsvFile(csvFile);

        assertThat(transactions).isEmpty();
    }

    @Test
    void testParseCsvFileNotFound() {
        Path nonExistentFile = tempDir.resolve("non_existent.csv");
        
        assertThatThrownBy(() -> csvParserService.parseCsvFile(nonExistentFile))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("CSV file not found");
    }
}