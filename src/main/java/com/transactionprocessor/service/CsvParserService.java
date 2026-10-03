package com.transactionprocessor.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
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
import org.mozilla.universalchardet.UniversalDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;

/**
 * Service for parsing CSV files containing transaction records
 */
public class CsvParserService {
    private static final Logger logger = LoggerFactory.getLogger(CsvParserService.class);
    
    private final List<DateTimeFormatter> dateFormatters;

    public CsvParserService() {
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

        byte[] content = Files.readAllBytes(csvFilePath);
        Charset charset = detectCharset(content);
        logger.info("Detected CSV charset {} for {}", charset.name(), csvFilePath);

        try (Reader reader = toUtf8Reader(content, charset)) {
            return parseCsvFromReader(reader, csvFilePath.toString());
        }
    }

    /**
     * Parse CSV from input stream (classpath resource)
     */
    public List<Transaction> parseCsvFile(InputStream inputStream) throws IOException {
        logger.info("Parsing CSV from input stream");
        
        byte[] content;
        try (inputStream) {
            content = inputStream.readAllBytes();
        }
        Charset charset = detectCharset(content);
        logger.info("Detected CSV charset {} for input stream", charset.name());

        try (Reader reader = toUtf8Reader(content, charset)) {
            return parseCsvFromReader(reader, "classpath resource");
        }
    }

    private Reader toUtf8Reader(byte[] content, Charset sourceCharset) {
        byte[] utf8Content = new String(content, sourceCharset).getBytes(StandardCharsets.UTF_8);
        return new InputStreamReader(new ByteArrayInputStream(utf8Content), StandardCharsets.UTF_8);
    }

    private Charset detectCharset(byte[] content) throws IOException {
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(content, 0, content.length);
        detector.dataEnd();
        String detectedName = detector.getDetectedCharset();
        detector.reset();

        if (detectedName == null || detectedName.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(detectedName);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IOException("Unsupported detected CSV charset: " + detectedName, e);
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
        Iterable<CSVRecord> csvRecords = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
            .parse(reader);
            
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
                } else if (values.length == 1 && values[0].trim().matches("(?i)fee\\s*=.*")
                        && !transactions.isEmpty()) {
                    Transaction previousTransaction = transactions.get(transactions.size() - 1);
                    String remarks = previousTransaction.getRemarks();
                    previousTransaction.setRemarks((remarks == null || remarks.isBlank())
                        ? values[0].trim()
                        : remarks + " " + values[0].trim());
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
            if (amount == null && transaction.getType() == TransactionType.DIVIDEND && quantity.signum() > 0
                    && fields.length > 7) {
                amount = deriveDividendAmount(fields[7], quantity);
            }
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

            StringBuilder rawRecord = new StringBuilder();
            try (org.apache.commons.csv.CSVPrinter printer = new org.apache.commons.csv.CSVPrinter(
                    rawRecord, CSVFormat.DEFAULT)) {
                printer.printRecord((Object[]) fields);
            }
            transaction.setRawLine(rawRecord.toString().stripTrailing());
            
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

    private BigDecimal deriveDividendAmount(String description, BigDecimal quantity) {
        if (description == null || description.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("每10股股息\\s*([0-9]+(?:\\.[0-9]+)?)")
            .matcher(description);
        if (!matcher.find()) {
            return null;
        }
        return new BigDecimal(matcher.group(1)).multiply(quantity)
            .divide(BigDecimal.TEN, 12, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** Create the supported input date formats. */
    private List<DateTimeFormatter> createDateFormatters() {
        return List.of(
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"));
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