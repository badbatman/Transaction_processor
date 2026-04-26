package com.transactionprocessor.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;

/**
 * Service for parsing CSV files containing transaction records
 */
public class CsvParserService {
    private static final Logger logger = LoggerFactory.getLogger(CsvParserService.class);
    
    private final ApplicationConfig config;
    private final List<DateTimeFormatter> dateFormatters;

    public CsvParserService(ApplicationConfig config) {
        this.config = config;
        this.dateFormatters = createDateFormatters();
    }

    /**
     * Parse CSV file and extract transaction records
     */
    public List<Transaction> parseCsvFile(Path csvFilePath) throws IOException {
        logger.info("Parsing CSV file: {}", csvFilePath);
        
        if (!Files.exists(csvFilePath)) {
            throw new IOException("CSV file not found: " + csvFilePath);
        }

        try (Reader reader = Files.newBufferedReader(csvFilePath, StandardCharsets.UTF_8)) {
            return parseCsvFromReader(reader, csvFilePath.toString());
        }
    }

    /**
     * Parse CSV from input stream (classpath resource)
     */
    public List<Transaction> parseCsvFile(InputStream inputStream) throws IOException {
        logger.info("Parsing CSV from input stream");
        
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return parseCsvFromReader(reader, "classpath resource");
        }
    }

    /**
     * Parse CSV from reader (common implementation)
     */
    private List<Transaction> parseCsvFromReader(Reader reader, String sourceName) throws IOException {
        // First, read all lines and find the transaction records section
        BufferedReader bufferedReader = new BufferedReader(reader);
        List<String> lines = new ArrayList<>();
        String line;
        int transactionSectionStartIndex = -1;
        
        while ((line = bufferedReader.readLine()) != null) {
            lines.add(line);
            // Look for transaction records header (交易记录 or 交易記錄)
            if (line.trim().contains("交易记录") || line.trim().contains("交易記錄")) {
                transactionSectionStartIndex = lines.size() - 1; // 0-based index
                logger.debug("Found transaction records section at line: {}", transactionSectionStartIndex + 1);
            }
        }
        
        if (transactionSectionStartIndex == -1) {
            logger.warn("No transaction records section found in CSV file: {}. Using entire file as transaction data.", sourceName);
            // No transaction section header found, use entire file
            StringBuilder allData = new StringBuilder();
            for (String l : lines) {
                allData.append(l).append("\n");
            }
            try (Reader allDataReader = new java.io.StringReader(allData.toString())) {
                return parseTransactionSection(allDataReader, sourceName);
            }
        }
        
        // Skip lines before transaction section and use the transaction header row
        StringBuilder transactionData = new StringBuilder();
        for (int i = transactionSectionStartIndex; i < lines.size(); i++) {
            transactionData.append(lines.get(i)).append("\n");
        }
        
        // Now parse only the transaction section
        try (Reader transactionReader = new java.io.StringReader(transactionData.toString())) {
            return parseTransactionSection(transactionReader, sourceName);
        }
    }
    
    /**
     * Parse only the transaction records section of CSV
     */
    private List<Transaction> parseTransactionSection(Reader reader, String sourceName) throws IOException {
        // Use Apache Commons CSV to properly parse CSV with quoted fields
        Iterable<CSVRecord> csvRecords = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader);
            
        List<Transaction> transactions = new ArrayList<>();
        int lineNumber = 1; // Start after header
            
        for (CSVRecord record : csvRecords) {
            try {
                logger.debug("Processing CSV record at line {}: {}", lineNumber + 1, record.toString());
                    
                // Get all fields from the record
                String[] values = new String[record.size()];
                for (int i = 0; i < record.size(); i++) {
                    values[i] = record.get(i);
                }
                    
                if (values.length >= 7) { // Need at least the required fields
                    Transaction transaction = parseTransactionFromArray(values, lineNumber + 1);
                    if (transaction != null) {
                        transactions.add(transaction);
                        logger.info("Successfully parsed transaction {}: {} ({})", transactions.size(), transaction.getName(), transaction.getType());
                    }
                } else {
                    logger.debug("Skipping line {} with insufficient fields ({}): {}", lineNumber + 1, values.length, java.util.Arrays.toString(values));
                }
            } catch (Exception e) {
                logger.warn("Failed to parse transaction at line {}: {}", lineNumber + 1, e.getMessage(), e);
            }
            lineNumber++;
        }
        
        logger.info("Successfully parsed {} transactions from {}", transactions.size(), sourceName);
        return transactions;
    }

    /**
     * Parse transaction from string array (for concatenated data)
     */
    private Transaction parseTransactionFromArray(String[] fields, int lineNumber) {
        try {
            logger.debug("Parsing transaction from array with {} fields: {}", fields.length, java.util.Arrays.toString(fields));
            
            // Validate required fields
            if (fields.length < 7) {
                logger.debug("Not enough fields in transaction array: {}", fields.length);
                return null;
            }
            
            Transaction transaction = new Transaction();
            transaction.setLineNumber(lineNumber);
            
            // Parse basic fields
            String name = fields[0].trim();
            String code = fields[1].trim();
            String typeStr = fields[2].trim();
            String dateStr = fields[3].trim();
            String priceStr = fields[4].trim();
            String quantityStr = fields[5].trim();
            String amountStr = fields[6].trim();
            
            logger.debug("Raw fields - Name: '{}', Code: '{}', Type: '{}', Date: '{}', Price: '{}', Quantity: '{}', Amount: '{}'", 
                        name, code, typeStr, dateStr, priceStr, quantityStr, amountStr);
            
            transaction.setName(name);
            transaction.setCode(code);
            
            // Parse transaction type
            try {
                TransactionType type = TransactionType.fromString(typeStr);
                transaction.setType(type);
                logger.debug("Parsed transaction type: {}", type);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown transaction type '{}' at line {}, skipping record", typeStr, lineNumber);
                return null;
            }
            
            // Parse date
            LocalDate date = parseDate(dateStr);
            if (date == null) {
                logger.warn("Invalid date format '{}' at line {}, skipping record", dateStr, lineNumber);
                return null;
            }
            transaction.setDate(date);
            logger.debug("Parsed date: {}", date);
            
            // Parse price
            BigDecimal price = parseDecimal(priceStr);
            if (price == null) {
                logger.warn("Invalid price '{}' at line {}, skipping record", priceStr, lineNumber);
                return null;
            }
            transaction.setPrice(price);
            logger.debug("Parsed price: {}", price);
            
            // Parse quantity
            BigDecimal quantity = parseDecimal(quantityStr);
            if (quantity == null) {
                logger.warn("Invalid quantity '{}' at line {}, skipping record", quantityStr, lineNumber);
                return null;
            }
            // For dividend transactions, quantity can be 0
            if (quantity.signum() < 0) {
                logger.warn("Negative quantity '{}' at line {}, skipping record", quantityStr, lineNumber);
                return null;
            }
            transaction.setQuantity(quantity);
            logger.debug("Parsed quantity: {}", quantity);
            
            // Parse amount
            BigDecimal amount = parseDecimal(amountStr);
            if (amount == null) {
                logger.warn("Invalid amount '{}' at line {}, skipping record", amountStr, lineNumber);
                return null;
            }
            transaction.setAmount(amount);
            logger.debug("Parsed amount: {}", amount);
            
            // Parse optional fields
            if (fields.length > 7) {
                transaction.setDescription(fields[7].trim());
                logger.debug("Parsed description: {}", fields[7].trim());
            }
            
            if (fields.length > 8) {
                transaction.setRemarks(fields[8].trim());
                logger.debug("Parsed remarks: {}", fields[8].trim());
            }
            
            logger.debug("Successfully parsed transaction: {}", transaction);
            return transaction;
            
        } catch (Exception e) {
            logger.error("Error parsing transaction from array at line {}: {}", lineNumber, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Parse a single CSV record into a Transaction object
     */
    private Transaction parseTransaction(CSVRecord record, int lineNumber) {
        try {
            logger.debug("Attempting to parse transaction at line {}: {}", lineNumber, record.toString());
            
            // Validate required fields
            if (!hasRequiredFields(record)) {
                logger.debug("Missing required fields at line {}, skipping record", lineNumber);
                return null;
            }

            Transaction transaction = new Transaction();
            transaction.setLineNumber(lineNumber);
            
            // Parse basic fields
            transaction.setName(record.get("名称").trim());
            transaction.setCode(record.get("代码").trim());
            
            // Parse transaction type
            String typeStr = record.get("类型").trim();
            try {
                TransactionType type = TransactionType.fromString(typeStr);
                transaction.setType(type);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown transaction type '{}' at line {}, skipping record", typeStr, lineNumber);
                return null;
            }
            
            // Parse date
            String dateStr = record.get("日期").trim();
            LocalDate date = parseDate(dateStr);
            if (date == null) {
                logger.warn("Invalid date format '{}' at line {}, skipping record", dateStr, lineNumber);
                return null;
            }
            transaction.setDate(date);
            
            // Parse price
            String priceStr = record.get("成交价").trim();
            BigDecimal price = parseDecimal(priceStr);
            transaction.setPrice(price);
            
            // Parse quantity
            String quantityStr = record.get("数量").trim();
            BigDecimal quantity = parseDecimal(quantityStr);
            transaction.setQuantity(quantity);
            
            // Parse amount
            String amountStr = record.get("金额").trim();
            BigDecimal amount = parseDecimal(amountStr);
            transaction.setAmount(amount);
            
            // Parse optional fields
            if (record.isMapped("说明")) {
                transaction.setDescription(record.get("说明").trim());
            }
            
            if (record.isMapped("备注")) {
                transaction.setRemarks(record.get("备注").trim());
            }
            
            // Store raw line for error reporting
            transaction.setRawLine(record.toString());
            
            logger.debug("Parsed transaction: {}", transaction);
            return transaction;
            
        } catch (Exception e) {
            logger.error("Error parsing transaction record at line {}: {}", lineNumber, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Check if the record has all required fields
     */
    private boolean hasRequiredFields(CSVRecord record) {
        //名称,代码,类型,日期,成交价,数量,金额,说明,备注
        String[] requiredHeaders = {"名称", "代码", "类型", "日期", "成交价", "数量", "金额"};
        
        logger.debug("Checking required fields for record: {}", java.util.Arrays.toString(record.values()));
        
        for (String header : requiredHeaders) {
            try {
                String value = record.get(header);
                logger.debug("Field '{}' has value: '{}' (empty: {})", header, value, (value == null || value.trim().isEmpty()));
                if (value == null || value.trim().isEmpty()) {
                    logger.debug("Required field '{}' is missing or empty", header);
                    return false;
                }
            } catch (IllegalArgumentException e) {
                // Header not found in record
                logger.debug("Required header '{}' not found in record", header);
                return false;
            }
        }
        
        logger.debug("All required fields present");
        return true;
    }

    /**
     * Parse date from various input types (String, Double, Long)
     * Distinguishes between dates (numeric) and plain numbers (like years)
     *
     * Excel serial date range: ~1-60000 (representing 1900-2063)
     * Plain numbers (year field): 1900-2100
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }

        String trimmedDate = dateStr.trim();

        // First, try parsing as string date in known formats
        for (DateTimeFormatter formatter : dateFormatters) {
            try {
                return LocalDate.parse(trimmedDate, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }

        // If string parsing failed, try parsing as numeric date
        try {
            // Try to convert string to number
            double numValue = Double.parseDouble(trimmedDate);
            return convertNumericToDate(numValue);
        } catch (NumberFormatException e) {
            // Not a number, log and return null
        }

        logger.warn("Unable to parse date: {}", dateStr);
        return null;
    }

    /**
     * Parse date from numeric value
     * Supports: Excel serial dates, Unix timestamps (milliseconds), and plain years
     *
     * @param numValue numeric value representing a date
     * @return LocalDate if recognized as date, null if it's a plain number
     */
    public LocalDate convertNumericToDate(double numValue) {
        // Excel serial date range: ~1-60000 represents approximately 1900-2063
        // Dates are stored as days since 1900-01-01 (with 1900-01-01 = 1)

        if (numValue >= 1 && numValue <= 60000) {
            // This is likely an Excel serial date
            logger.debug("Converting Excel serial date: {} to LocalDate", numValue);
            try {
                // Excel epoch: 1900-01-01 (accounting for Excel's leap year bug)
                long daysSinceEpoch = Math.round(numValue);

                // Excel has a bug: it considers 1900 as a leap year (it's not)
                // So we need to adjust for dates after Feb 28, 1900
                if (daysSinceEpoch >= 60) {
                    daysSinceEpoch--; // Adjust for Excel's leap year bug
                }

                LocalDate excelEpoch = LocalDate.of(1900, 1, 1);
                LocalDate result = excelEpoch.plusDays(daysSinceEpoch - 1);
                logger.debug("Excel serial date {} converted to: {}", numValue, result);
                return result;
            } catch (Exception e) {
                logger.warn("Failed to convert Excel serial date {}: {}", numValue, e.getMessage());
            }
        }

        // Unix timestamp (milliseconds): approximately 1000000000000 to 2000000000000
        else if (numValue >= 1000000000000L && numValue <= 2000000000000L) {
            logger.debug("Converting Unix timestamp (ms): {} to LocalDate", numValue);
            try {
                long milliseconds = Math.round(numValue);
                return Instant.ofEpochMilli(milliseconds)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            } catch (Exception e) {
                logger.warn("Failed to convert Unix timestamp {}: {}", numValue, e.getMessage());
            }
        }

        // Unix timestamp (seconds): approximately 1000000000 to 2000000000
        else if (numValue >= 1000000000 && numValue < 1000000000000L) {
            logger.debug("Converting Unix timestamp (sec): {} to LocalDate", numValue);
            try {
                long seconds = Math.round(numValue);
                return Instant.ofEpochSecond(seconds)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            } catch (Exception e) {
                logger.warn("Failed to convert Unix timestamp (sec) {}: {}", numValue, e.getMessage());
            }
        }

        // Plain number like year (1900-2100) - NOT a date
        else if (numValue >= 1900 && numValue <= 2100) {
            logger.debug("Value {} is a year/plain number, not a date", numValue);
            return null;
        }

        // Unknown format
        logger.warn("Numeric value {} doesn't match any known date format", numValue);
        return null;
    }

    /**
     * Parse decimal number, handling currency symbols and formatting
     */
    private BigDecimal parseDecimal(String numberStr) {
        if (numberStr == null || numberStr.trim().isEmpty()) {
            return null;
        }
        
        try {
            // Remove currency symbols, whitespace, and common formatting
            String cleaned = numberStr.trim()
                    .replace("¥", "")
                    .replace("HK$", "")
                    .replace("$", "")
                    .replace("€", "")
                    .replace("£", "")
                    .replace(",", "")
                    .replace("'", "")  // Some spreadsheets use ' as thousand separator
                    .replace(" ", "")   // Remove spaces
                    .trim();
            
            // Handle empty string after cleaning
            if (cleaned.isEmpty()) {
                return null;
            }
            
            // Check if it's a formula (starts with =)
            if (cleaned.startsWith("=")) {
                logger.debug("Detected formula '{}', attempting to extract numeric value", cleaned);
                // Try to extract the numeric part from simple formulas like =A1*2 or =SUM(A1:A3)
                // For now, just return null and let caller handle it
                return null;
            }
            
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            logger.warn("Unable to parse number '{}': {}", numberStr, e.getMessage());
            return null;
        }
    }

    /**
     * Create date formatters from configuration
     */
    private List<DateTimeFormatter> createDateFormatters() {
        List<DateTimeFormatter> formatters = new ArrayList<>();
        
        for (String pattern : config.getCsvDateFormats()) {
            try {
                formatters.add(DateTimeFormatter.ofPattern(pattern.trim()));
            } catch (Exception e) {
                logger.warn("Invalid date format pattern: {}", pattern);
            }
        }
        
        // Add default formatters if none configured
        if (formatters.isEmpty()) {
            formatters.add(DateTimeFormatter.ofPattern("yyyy/M/d"));
            formatters.add(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            formatters.add(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            formatters.add(DateTimeFormatter.ofPattern("yyyy-M-d"));
        }
        
        return formatters;
    }

    /**
     * Validate transaction data
     */
    public boolean validateTransaction(Transaction transaction) {
        if (transaction == null) {
            return false;
        }
        
        // Check required fields
        if (transaction.getName() == null || transaction.getName().trim().isEmpty() ||
            transaction.getCode() == null || transaction.getCode().trim().isEmpty() ||
            transaction.getType() == null ||
            transaction.getDate() == null ||
            transaction.getPrice() == null || transaction.getPrice().signum() < 0 ||
            transaction.getQuantity() == null || transaction.getQuantity().signum() <= 0 ||
            transaction.getAmount() == null) {
            return false;
        }
        
        // Validate transaction-specific logic
        switch (transaction.getType()) {
            case BUY:
            case SELL:
                // For buy/sell, price should be positive
                if (transaction.getPrice().signum() <= 0) {
                    return false;
                }
                break;
            case DIVIDEND:
                // For dividend, price is typically 0
                if (transaction.getPrice() != null && transaction.getPrice().signum() != 0) {
                    logger.warn("Dividend transaction with non-zero price: {}", transaction.getPrice());
                }
                break;
        }
        
        return true;
    }

    /**
     * Filter transactions by date range
     */
    public List<Transaction> filterByDateRange(List<Transaction> transactions, LocalDate startDate, LocalDate endDate) {
        List<Transaction> filtered = new ArrayList<>();
        
        for (Transaction transaction : transactions) {
            LocalDate transactionDate = transaction.getDate();
            if (transactionDate != null && 
                !transactionDate.isBefore(startDate) && 
                !transactionDate.isAfter(endDate)) {
                filtered.add(transaction);
            }
        }
        
        return filtered;
    }
    

}