package com.transactionprocessor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.model.ProcessingResult;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.service.CodeLabelMappingService;
import com.transactionprocessor.service.CsvParserService;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.service.TransactionProcessor;
import com.transactionprocessor.service.VerificationService;
import com.transactionprocessor.service.BusinessSummaryService;

/**
 * Main application class for Transaction Processor
 */
public class MainApplication {
    private static final Logger logger = LoggerFactory.getLogger(MainApplication.class);
    
    private final ApplicationConfig config;
    private final CsvParserService csvParserService;
    private final CodeLabelMappingService codeLabelMappingService;
    private final GoogleSheetsService googleSheetsService;
    private final TransactionProcessor transactionProcessor;
    private final VerificationService verificationService;
    private final BusinessSummaryService businessSummaryService;

    public MainApplication() throws IOException, GeneralSecurityException {
        logger.info("Initializing Transaction Processor application");
        logger.info("Current working directory: {}", System.getProperty("user.dir"));
        
        // Initialize configuration
        this.config = new ApplicationConfig();
        
        // Initialize services
        this.csvParserService = new CsvParserService(config);
        
        // Initialize code label mapping service
        InputStream mappingStream = getClass().getClassLoader().getResourceAsStream("code_label_mapping.txt");
        if (mappingStream == null) {
            logger.warn("code_label_mapping.txt not found in classpath, trying file system");
            Path mappingFilePath = Paths.get("code_label_mapping.txt");
            this.codeLabelMappingService = new CodeLabelMappingService(mappingFilePath);
        } else {
            this.codeLabelMappingService = new CodeLabelMappingService(mappingStream);
        }
        this.codeLabelMappingService.loadMappings();
        
        // Initialize Google Sheets service
        GoogleSheetsConfig googleSheetsConfig = new GoogleSheetsConfig(config);
        this.googleSheetsService = new GoogleSheetsService(googleSheetsConfig);
        
        // Initialize transaction processor
        this.transactionProcessor = new TransactionProcessor(
            config, googleSheetsService, codeLabelMappingService
        );
        
        // Initialize verification and business summary services
        this.verificationService = new VerificationService(googleSheetsService);
        this.businessSummaryService = new BusinessSummaryService();
        
        logger.info("Transaction Processor application initialized successfully");
    }

    /**
     * Process transactions for a specific month and sheet
     */
    public ProcessingResult processMonth(String sheetName, YearMonth yearMonth, String spreadsheetId) {
        logger.info("Processing transactions for sheet: {} and month: {}", sheetName, yearMonth);
        
        ProcessingResult result = new ProcessingResult(sheetName);
        
        try {
            // Construct CSV file path based on sheet name and month
            String csvFileName = String.format("%s_%04d%02d.csv",
                sheetName,
                yearMonth.getYear(),
                yearMonth.getMonthValue());
            
            // Try file system first, then fall back to classpath
            Path csvFilePath = Paths.get(csvFileName);
            logger.info("Looking for CSV file at: {} (exists: {})", csvFilePath.toAbsolutePath(), Files.exists(csvFilePath));
            List<Transaction> transactions;
            
            if (Files.exists(csvFilePath)) {
                // Load from file system
                logger.info("Loading CSV from file system: {}", csvFilePath.toAbsolutePath());
                transactions = csvParserService.parseCsvFile(csvFilePath);
            } else {
                // Fall back to classpath
                logger.debug("CSV file not found in file system, checking classpath");
                InputStream csvStream = getClass().getClassLoader().getResourceAsStream(csvFileName);
                if (csvStream != null) {
                    logger.info("Loading CSV from classpath: {}", csvFileName);
                    transactions = csvParserService.parseCsvFile(csvStream);
                } else {
                    logger.warn("CSV file not found in file system or classpath: {}", csvFileName);
                    result.complete();
                    return result;
                }
            }
            
            // Filter transactions to only include data for the specified month
            List<Transaction> filteredTransactions = transactions.stream()
                .filter(tx -> YearMonth.from(tx.getDate()).equals(yearMonth))
                .collect(java.util.stream.Collectors.toList());
            
            logger.info("Filtered to {} transactions for {} (out of {} total)", 
                       filteredTransactions.size(), yearMonth, transactions.size());
            
            result.setTotalTransactions(filteredTransactions.size());
            
            if (filteredTransactions.isEmpty()) {
                logger.warn("No transactions found for {} in CSV file: {}", yearMonth, csvFileName);
                result.complete();
                return result;
            }
            
            // Sort transactions by date to ensure buys come before sells
            List<Transaction> sortedTransactions = new ArrayList<>(filteredTransactions);
            sortedTransactions.sort((t1, t2) -> t1.getDate().compareTo(t2.getDate()));
            
            logger.info("Processing {} transactions for {} sorted by date", sortedTransactions.size(), yearMonth);
            
            // Apply code to label mappings
            codeLabelMappingService.applyLabelMappings(sortedTransactions);
            
            // Process transactions
            ProcessingResult sheetResult = transactionProcessor.processTransactions(
                spreadsheetId, sheetName, sortedTransactions
            );
            
            // Merge results
            result.getSuccessfulTransactionsList().addAll(sheetResult.getSuccessfulTransactionsList());
            result.getFailedTransactionsList().addAll(sheetResult.getFailedTransactionsList());
            result.setSuccessfulTransactions(sheetResult.getSuccessfulTransactions());
            result.setFailedTransactions(sheetResult.getFailedTransactions());
            result.incrementApiCalls(sheetResult.getGoogleApiCalls());
            
            logger.info("Completed processing for sheet: {}. Success rate: {:.2f}%", 
                sheetName, result.getSuccessRate());
            
        } catch (Exception e) {
            logger.error("Error processing transactions for sheet: " + sheetName, e);
            result.setErrorMessage(e.getMessage());
        } finally {
            result.complete();
        }
        
        return result;
    }

    /**
     * Process multiple months for multiple sheets
     */
    public List<ProcessingResult> processMultipleMonths(List<String> sheetNames, 
                                                       List<YearMonth> months, 
                                                       String spreadsheetId) {
        logger.info("Processing {} sheets for {} months", sheetNames.size(), months.size());
        
        // Wire up verification and business summary services
        transactionProcessor.setVerificationService(verificationService);
        transactionProcessor.setBusinessSummaryService(businessSummaryService);
        
        List<ProcessingResult> allResults = new ArrayList<>();
        
        for (String sheetName : sheetNames) {
            for (YearMonth month : months) {
                ProcessingResult result = processMonth(sheetName, month, spreadsheetId);
                allResults.add(result);
                
                // Log progress
                logger.info("Processed {} - {}: {} successful, {} failed", 
                    sheetName, month, result.getSuccessfulTransactions(), result.getFailedTransactions());
            }
        }
        
        // Generate and log verification report (Requirement.md 122-126)
        if (verificationService != null) {
            verificationService.logVerificationSummary();
            logger.info("\n{}", verificationService.generateVerificationReport());
        }
        
        // Generate and log business summary report
        if (businessSummaryService != null) {
            businessSummaryService.logSummary();
            logger.info("\n{}", businessSummaryService.generateSummaryReport());
        }
        
        return allResults;
    }

    /**
     * Check if header row (skip in processing)
     */
    private boolean isHeaderRow(List<Object> rowData) {
        if (rowData == null || rowData.isEmpty()) {
            return false;
        }
        
        String firstCell = rowData.get(0).toString().toLowerCase();
        return firstCell.contains("label") || firstCell.contains("名称");
    }

    /**
     * Print processing summary
     */
    public void printSummary(List<ProcessingResult> results) {
        logger.info("=== TRANSACTION PROCESSING SUMMARY ===");
        
        int totalTransactions = 0;
        int totalSuccessful = 0;
        int totalFailed = 0;
        int totalApiCalls = 0;
        long totalProcessingTime = 0;
        
        for (ProcessingResult result : results) {
            totalTransactions += result.getTotalTransactions();
            totalSuccessful += result.getSuccessfulTransactions();
            totalFailed += result.getFailedTransactions();
            totalApiCalls += result.getGoogleApiCalls();
            totalProcessingTime += result.getProcessingTimeMillis();
            
            logger.info("{} - {}: {} transactions, {} successful, {} failed, {} API calls, {}ms",
                result.getSheetName(),
                result.getProcessingStartTime().toLocalDate(),
                result.getTotalTransactions(),
                result.getSuccessfulTransactions(),
                result.getFailedTransactions(),
                result.getGoogleApiCalls(),
                result.getProcessingTimeMillis()
            );
            
            if (result.getErrorMessage() != null) {
                logger.error("Error in {}: {}", result.getSheetName(), result.getErrorMessage());
            }
        }
        
        logger.info("=== OVERALL SUMMARY ===");
        logger.info("Total Transactions: {}", totalTransactions);
        logger.info("Successful: {} ({:.2f}%)", totalSuccessful, 
            totalTransactions > 0 ? (double) totalSuccessful / totalTransactions * 100 : 0);
        logger.info("Failed: {} ({:.2f}%)", totalFailed,
            totalTransactions > 0 ? (double) totalFailed / totalTransactions * 100 : 0);
        logger.info("Total API Calls: {}", totalApiCalls);
        logger.info("Total Processing Time: {}ms", totalProcessingTime);
        logger.info("Average Processing Time: {}ms", 
            results.size() > 0 ? totalProcessingTime / results.size() : 0);
        
        // Log failed transactions details
        if (totalFailed > 0) {
            logger.info("=== FAILED TRANSACTIONS DETAILS ===");
            for (ProcessingResult result : results) {
                if (!result.getFailedTransactionsList().isEmpty()) {
                    logger.info("Failed transactions for {}:", result.getSheetName());
                    for (ProcessingResult.FailedTransaction failed : result.getFailedTransactionsList()) {
                        logger.error("  Line {}: {} - {}", 
                            failed.getTransaction().getLineNumber(),
                            failed.getTransaction().toString(),
                            failed.getErrorMessage());
                    }
                }
            }
        }
    }

    /**
     * Discover sheet names from available CSV files in the working directory
     */
    private List<String> discoverSheetNamesFromCsvFiles() {
        List<String> sheetNames = new ArrayList<>();
        Pattern pattern = Pattern.compile("^(.+?)_\\d{6}\\.csv$");
        
        try {
            Path workingDir = Paths.get("");
            Files.list(workingDir)
                .filter(path -> path.getFileName().toString().endsWith(".csv"))
                .forEach(path -> {
                    String fileName = path.getFileName().toString();
                    Matcher matcher = pattern.matcher(fileName);
                    if (matcher.matches()) {
                        String sheetName = matcher.group(1);
                        if (!sheetNames.contains(sheetName)) {
                            sheetNames.add(sheetName);
                            logger.info("Discovered sheet name from CSV file: {} -> {}", fileName, sheetName);
                        }
                    }
                });
        } catch (IOException e) {
            logger.warn("Failed to discover sheet names from CSV files", e);
        }
        
        if (sheetNames.isEmpty()) {
            logger.warn("No CSV files found with naming pattern <sheetName>_YYYYMM.csv");
        }
        
        return sheetNames;
    }
    
    /**
     * Discover months from available CSV files for given sheet names
     */
    private List<YearMonth> discoverMonthsFromCsvFiles(List<String> sheetNames) {
        List<YearMonth> months = new ArrayList<>();
        Pattern pattern = Pattern.compile("^.+?_(\\d{4})(\\d{2})\\.csv$");
        
        try {
            Path workingDir = Paths.get("");
            Files.list(workingDir)
                .filter(path -> path.getFileName().toString().endsWith(".csv"))
                .forEach(path -> {
                    String fileName = path.getFileName().toString();
                    Matcher matcher = pattern.matcher(fileName);
                    if (matcher.matches()) {
                        try {
                            int year = Integer.parseInt(matcher.group(1));
                            int month = Integer.parseInt(matcher.group(2));
                            YearMonth yearMonth = YearMonth.of(year, month);
                            if (!months.contains(yearMonth)) {
                                months.add(yearMonth);
                                logger.info("Discovered month from CSV file: {} -> {}", fileName, yearMonth);
                            }
                        } catch (NumberFormatException e) {
                            logger.debug("Failed to parse year/month from filename: {}", fileName);
                        }
                    }
                });
        } catch (IOException e) {
            logger.warn("Failed to discover months from CSV files", e);
        }
        
        // Sort months chronologically
        months.sort(null);
        
        if (months.isEmpty()) {
            logger.warn("No valid month information found in CSV filenames");
        }
        
        return months;
    }

    /**
     * Main method for command line execution
     */
    public static void main(String[] args) {
        try {
            MainApplication app = new MainApplication();
            
            // Load configuration from properties
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(Paths.get("google-sheets.properties"))) {
                props.load(input);
            }
            
            // Get spreadsheet ID with fallback chain:
            // 1. System property (-Ddefault.spreadsheet.id)
            // 2. Environment variable (DEFAULT_SPREADSHEET_ID)
            // 3. Properties file
            String spreadsheetId = System.getProperty("default.spreadsheet.id",
                    System.getenv("DEFAULT_SPREADSHEET_ID") != null ? 
                        System.getenv("DEFAULT_SPREADSHEET_ID") : 
                        props.getProperty("default.spreadsheet.id"));
            
            if (spreadsheetId == null || spreadsheetId.isEmpty()) {
                throw new IllegalStateException(
                    "Spreadsheet ID not configured. Set DEFAULT_SPREADSHEET_ID environment variable, " +
                    "use -Ddefault.spreadsheet.id system property, or set it in google-sheets.properties");
            }
            
            // Discover sheets and months from available CSV files
            List<String> sheetNames = app.discoverSheetNamesFromCsvFiles();
            List<YearMonth> months = app.discoverMonthsFromCsvFiles(sheetNames);
            
            logger.info("Discovered configuration:");
            logger.info("- Spreadsheet ID: {}", spreadsheetId);
            logger.info("- Sheet Names: {}", sheetNames);
            logger.info("- Months to process: {}", months);
            
            // Process transactions
            List<ProcessingResult> results = app.processMultipleMonths(sheetNames, months, spreadsheetId);
            
            // Print summary
            app.printSummary(results);
            
            // Exit with appropriate code
            boolean hasErrors = results.stream().anyMatch(r -> r.getFailedTransactions() > 0);
            System.exit(hasErrors ? 1 : 0);
            
        } catch (Exception e) {
            logger.error("Application failed to start", e);
            System.exit(1);
        }
    }
}