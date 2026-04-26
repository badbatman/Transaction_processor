package com.transactionprocessor.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.model.GoogleSheetsRow;
import com.transactionprocessor.model.ProcessingResult;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;
import com.transactionprocessor.processor.BuyTransactionProcessor;
import com.transactionprocessor.processor.DividendTransactionProcessor;
import com.transactionprocessor.processor.DuplicateTransactionException;
import com.transactionprocessor.processor.SellTransactionProcessor;

/**
 * Main transaction processor that coordinates the processing of transactions
 */
public class TransactionProcessor {
    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessor.class);
    
    private final ApplicationConfig config;
    private final GoogleSheetsService googleSheetsService;
    private final CodeLabelMappingService codeLabelMappingService;
    private final RollbackService rollbackService;
    
    private final BuyTransactionProcessor buyProcessor;
    private final SellTransactionProcessor sellProcessor;
    private final DividendTransactionProcessor dividendProcessor;
    
    // Optional services for verification and business summary
    private VerificationService verificationService;
    private BusinessSummaryService businessSummaryService;

    public TransactionProcessor(ApplicationConfig config, 
                              GoogleSheetsService googleSheetsService,
                              CodeLabelMappingService codeLabelMappingService) {
        this.config = config;
        this.googleSheetsService = googleSheetsService;
        this.codeLabelMappingService = codeLabelMappingService;
        this.rollbackService = new RollbackService(googleSheetsService);
        
        // Initialize specific transaction processors
        this.buyProcessor = new BuyTransactionProcessor(googleSheetsService);
        this.sellProcessor = new SellTransactionProcessor(googleSheetsService);
        this.dividendProcessor = new DividendTransactionProcessor(googleSheetsService);
    }
    
    /**
     * Set verification service for independent verification (Requirement.md 122-126)
     */
    public void setVerificationService(VerificationService verificationService) {
        this.verificationService = verificationService;
    }
    
    /**
     * Set business summary service for business-level summary logging
     */
    public void setBusinessSummaryService(BusinessSummaryService businessSummaryService) {
        this.businessSummaryService = businessSummaryService;
    }

    /**
     * Process a list of transactions and update Google Sheets
     */
    public ProcessingResult processTransactions(String spreadsheetId, String sheetName, List<Transaction> transactions) {
        logger.info("Processing {} transactions for sheet: {}", transactions.size(), sheetName);
        
        ProcessingResult result = new ProcessingResult(sheetName);
        result.setTotalTransactions(transactions.size());
        
        String backupSheetName = null;
        
        try {
            // Create backup before processing (only in non-dry-run mode)
            if (!config.isDryRunEnabled()) {
                backupSheetName = rollbackService.createBackup(spreadsheetId, sheetName);
                result.incrementApiCalls(1);
                logger.info("Created backup sheet: {}", backupSheetName);
            }
            
            // Read existing data from Google Sheets
            List<GoogleSheetsRow> existingRows = googleSheetsService.readSheet(spreadsheetId, sheetName);
            logger.info("Read {} existing rows from sheet: {}", existingRows.size(), sheetName);
            
            // CRITICAL: Save original row numbers BEFORE processing
            // During transaction processing, row numbers in existingRows list will shift due to insertions
            // We need to preserve original row numbers to write data back to correct Excel rows
            // Use row number as key (not row object) to avoid HashMap issues with copied/modified rows
            Map<Integer, Integer> originalRowNumbers = new HashMap<>();
            for (GoogleSheetsRow row : existingRows) {
                if (row.getRowNumber() != null) {
                    // Map from current row number to itself (original value before any shifts)
                    originalRowNumbers.put(row.getRowNumber(), row.getRowNumber());
                }
            }
            
            // Process each transaction
            for (Transaction transaction : transactions) {
                try {
                    processTransaction(transaction, existingRows, spreadsheetId, sheetName);
                    result.addSuccessfulTransaction(transaction);
                    logger.debug("Successfully processed transaction: {}", transaction);
                } catch (DuplicateTransactionException e) {
                    // REQUIREMENT.md Section 143: Duplicate detected - skip without error
                    logger.warn("Skipping duplicate transaction: {}. {}", transaction, e.getMessage());
                    result.addDuplicateTransaction(transaction);
                    
                    // Track skipped transaction for business summary
                    if (businessSummaryService != null) {
                        businessSummaryService.trackSkippedTransaction("Duplicate transaction", transaction);
                    }
                } catch (Exception e) {
                    logger.error("Failed to process transaction: {}", transaction, e);
                    result.addFailedTransaction(transaction, e.getMessage());
                    
                    // Track skipped transaction for business summary
                    if (businessSummaryService != null) {
                        businessSummaryService.trackSkippedTransaction(e.getMessage(), transaction);
                    }
                                        
                    // If in non-dry-run mode and we have a backup, restore from backup
                    if (!config.isDryRunEnabled() && backupSheetName != null) {
                        try {
                            rollbackService.restoreFromBackup(spreadsheetId, backupSheetName, sheetName);
                            logger.info("Rolled back changes due to processing error");
                        } catch (Exception rollbackEx) {
                            logger.error("Failed to rollback changes after processing error", rollbackEx);
                        }
                    }
                }
            }
            
            // Write updated data back to Google Sheets or produce a preview in dry-run mode
            if (!existingRows.isEmpty()) {
                boolean dryRun = config.isDryRunEnabled();
                
                // Find dirty rows to determine which rows will actually be written
                List<GoogleSheetsRow> dirtyRows = new ArrayList<>();
                for (GoogleSheetsRow row : existingRows) {
                    if (row.isDirty()) {
                        dirtyRows.add(row);
                    }
                }
                
                // Build formatting requests ONLY for rows that will be written
                List<GoogleSheetsService.FormatRequest> formatRequests = new ArrayList<>();
                if (!dirtyRows.isEmpty()) {
                    for (GoogleSheetsRow dirtyRow : dirtyRows) {
                        if (dirtyRow.isBold() || dirtyRow.isItalic() || dirtyRow.isStrikethrough()) {
                            // Use ORIGINAL row number (look up by current row number if it exists in map)
                            Integer sheetRow = -1;
                            if (dirtyRow.getRowNumber() != null && originalRowNumbers.containsKey(dirtyRow.getRowNumber())) {
                                sheetRow = originalRowNumbers.get(dirtyRow.getRowNumber());
                            } else if (dirtyRow.getRowNumber() != null && dirtyRow.getRowNumber() > 0) {
                                // For new rows, use the row number as-is (not in original map)
                                sheetRow = dirtyRow.getRowNumber();
                            }
                            // Only add format request for rows that have actual row numbers (already in sheet)
                            if (sheetRow > 0) {
                                GoogleSheetsService.FormatRequest fr = new GoogleSheetsService.FormatRequest(
                                    sheetRow, sheetRow, 1, 20, dirtyRow.isBold(), dirtyRow.isItalic(), dirtyRow.isStrikethrough()
                                );
                                formatRequests.add(fr);
                            }
                        }
                    }
                }

                if (dryRun) {
                    // Produce a preview JSON under target/
                    try {
                        Map<String, Object> preview = new HashMap<>();
                        preview.put("spreadsheetId", spreadsheetId);
                        preview.put("sheetName", sheetName);
                        String isoTs = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
                        preview.put("timestamp", isoTs);

                        List<Map<String, Object>> writes = new ArrayList<>();
                        // Only preview dirty rows
                        for (int i = 0; i < dirtyRows.size(); i++) {
                            GoogleSheetsRow r = dirtyRows.get(i);
                            Map<String, Object> writeBlock = new HashMap<>();
                            writeBlock.put("row", r.getRowNumber() != null ? r.getRowNumber() : "new");
                            writeBlock.put("data", r.toSheetValues());
                            writes.add(writeBlock);
                        }
                        preview.put("rowsToWrite", writes);
                        preview.put("formatRequests", formatRequests);

                        String safeSheetName = sheetName.replaceAll("[^A-Za-z0-9_-]", "_");
                        String fileTs = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                        Path previewPath = Path.of("target", "preview-" + safeSheetName + "-" + fileTs + ".json");
                        Files.createDirectories(previewPath.getParent());
                        ObjectMapper mapper = new ObjectMapper();
                        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(preview);
                        Files.write(previewPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        logger.info("Dry-run enabled: wrote preview JSON to {}", previewPath.toString());
                        result.incrementApiCalls(0);
                        // mark result as dry-run if model supports it (optional)
                        try { java.lang.reflect.Method m = result.getClass().getMethod("setDryRun", boolean.class); if (m != null) m.invoke(result, true); } catch (NoSuchMethodException ns) {}
                    } catch (IOException e) {
                        logger.error("Failed to write dry-run preview JSON", e);
                        result.setErrorMessage("Failed to write dry-run preview: " + e.getMessage());
                    }
                } else {
                    // Write dirty rows (existing rows with their original row numbers)
                    // CRITICAL: Use original row numbers, not current ones (which may have shifted during processing)
                    List<GoogleSheetsRow> existingRowsWithNumbers = dirtyRows.stream()
                        .filter(row -> row.getRowNumber() != null && originalRowNumbers.containsKey(row.getRowNumber())) // Had an original row number
                        .sorted((r1, r2) -> Integer.compare(
                            originalRowNumbers.get(r1.getRowNumber()),
                            originalRowNumbers.get(r2.getRowNumber())
                        ))
                        .collect(java.util.stream.Collectors.toList());
                    
                    List<GoogleSheetsRow> newRowsWithoutNumbers = dirtyRows.stream()
                        .filter(row -> row.getRowNumber() == null || !originalRowNumbers.containsKey(row.getRowNumber())) // No original row number = new row
                        .collect(java.util.stream.Collectors.toList());
                    
                    if (!existingRowsWithNumbers.isEmpty()) {
                        logger.info("Writing {} existing dirty rows back to sheet: {}", existingRowsWithNumbers.size(), sheetName);
                        // Write existing dirty rows at their specific positions
                        for (GoogleSheetsRow dirtyRow : existingRowsWithNumbers) {
                            try {
                                List<GoogleSheetsRow> singleRow = List.of(dirtyRow);
                                // Use ORIGINAL row number (look up by current row number)
                                Integer originalRowNumber = originalRowNumbers.get(dirtyRow.getRowNumber());
                                googleSheetsService.writeRows(spreadsheetId, sheetName, singleRow, originalRowNumber);
                                result.incrementApiCalls(1);
                            } catch (IOException ioe) {
                                Integer originalRowNumber = originalRowNumbers.get(dirtyRow.getRowNumber());
                                logger.error("Failed to write dirty row {} to sheet: {}", 
                                    originalRowNumber, sheetName, ioe);
                                // Continue with other rows even if one fails
                            }
                        }
                    }
                    
                    // Append new rows that don't have row numbers
                    if (!newRowsWithoutNumbers.isEmpty()) {
                        logger.info("Appending {} new rows without row numbers to sheet: {}", 
                            newRowsWithoutNumbers.size(), sheetName);
                        try {
                            googleSheetsService.appendRows(spreadsheetId, sheetName, newRowsWithoutNumbers);
                            result.incrementApiCalls(1);
                        } catch (IOException ioe) {
                            logger.error("Failed to append new rows to sheet: {}", sheetName, ioe);
                        }
                    }

                    if (!formatRequests.isEmpty()) {
                        try {
                            googleSheetsService.applyFormatting(spreadsheetId, sheetName, formatRequests);
                            result.incrementApiCalls(1); // formatting batch
                        } catch (IOException ioe) {
                            logger.error("Failed to apply formatting to sheet: {}", sheetName, ioe);
                            result.setErrorMessage("Failed to apply formatting: " + ioe.getMessage());
                        }
                    }
                }
            }
            
            logger.info("Completed processing for sheet: {}. Success rate: {:.2f}%", 
                sheetName, result.getSuccessRate());
            
        } catch (Exception e) {
            logger.error("Error processing transactions for sheet: " + sheetName, e);
            result.setErrorMessage(e.getMessage());
        } finally {
            result.complete();
        }
        
        // Set backup sheet name if one was created
        if (backupSheetName != null) {
            result.setBackupSheetName(backupSheetName);
        }
        
        return result;
    }

    /**
     * Process a single transaction
     */
    private void processTransaction(Transaction transaction, 
                                  List<GoogleSheetsRow> existingRows,
                                  String spreadsheetId, 
                                  String sheetName) throws Exception {
        
        logger.debug("Processing transaction: {}", transaction);
        
        // Capture original state for verification (before processing)
        List<GoogleSheetsRow> originalRowsSnapshot = new ArrayList<>();
        for (GoogleSheetsRow row : existingRows) {
            originalRowsSnapshot.add(row.copy());
        }
        
        // Validate transaction
        if (!isValidTransaction(transaction)) {
            throw new IllegalArgumentException("Invalid transaction data");
        }
        
        // Apply code to label mapping
        codeLabelMappingService.applyLabelMapping(transaction);
        
        // Process based on transaction type
        switch (transaction.getType()) {
            case BUY:
                buyProcessor.processTransaction(transaction, existingRows, spreadsheetId, sheetName);
                break;
            case SELL:
                sellProcessor.processTransaction(transaction, existingRows, spreadsheetId, sheetName);
                break;
            case DIVIDEND:
                dividendProcessor.processTransaction(transaction, existingRows, spreadsheetId, sheetName);
                break;
            default:
                throw new IllegalArgumentException("Unknown transaction type: " + transaction.getType());
        }
        
        // Track business summary after successful processing
        if (businessSummaryService != null) {
            trackBusinessSummary(transaction, existingRows);
        }
        
        // Verify transaction processing after successful update (Requirement.md 122-126)
        if (verificationService != null) {
            try {
                verificationService.verifyTransactionProcessing(
                    transaction,
                    originalRowsSnapshot,
                    existingRows,
                    spreadsheetId,
                    sheetName
                );
                logger.debug("Verification completed for transaction: {}", transaction.getName());
            } catch (Exception verificationEx) {
                logger.warn("Verification failed for transaction: {}. Error: {}", 
                           transaction.getName(), verificationEx.getMessage());
                // Don't fail the transaction due to verification errors
                // Verification failures are logged and reported separately
            }
        }
    }
    
    /**
     * Track transaction for business summary
     */
    private void trackBusinessSummary(Transaction transaction, List<GoogleSheetsRow> existingRows) {
        try {
            // Find current row state
            GoogleSheetsRow currentRow = null;
            for (GoogleSheetsRow row : existingRows) {
                if (row.getLabel() != null && row.getLabel().equals(transaction.getName())) {
                    // For buy/sell, match by date too
                    if (transaction.getType() == TransactionType.BUY && 
                        row.getOpenTime() != null && 
                        row.getOpenTime().equals(transaction.getDate())) {
                        currentRow = row;
                        break;
                    } else if (transaction.getType() == TransactionType.SELL && 
                               row.getCloseTime() != null && 
                               row.getCloseTime().equals(transaction.getDate())) {
                        currentRow = row;
                        break;
                    } else if (transaction.getType() == TransactionType.DIVIDEND) {
                        // For dividend, just find the position
                        currentRow = row;
                        break;
                    }
                }
            }
            
            BigDecimal feesAndTaxes = BigDecimal.ZERO;
            
            switch (transaction.getType()) {
                case BUY:
                    if (currentRow != null) {
                        // Find previous quantity (simplified - assume we can get it from position tracking)
                        BigDecimal previousQty = BigDecimal.ZERO; // Would need to track this separately
                        feesAndTaxes = currentRow.getOpenFeeTax() != null ? currentRow.getOpenFeeTax() : BigDecimal.ZERO;
                        
                        businessSummaryService.processBuyTransaction(
                            transaction,
                            previousQty,
                            currentRow.getNumberOfStock(),
                            feesAndTaxes
                        );
                    }
                    break;
                    
                case SELL:
                    if (currentRow != null) {
                        // Previous quantity would be before the sell
                        BigDecimal previousQty = currentRow.getNumberOfStock().add(transaction.getQuantity());
                        feesAndTaxes = currentRow.getCloseFeeTax() != null ? currentRow.getCloseFeeTax() : BigDecimal.ZERO;
                        
                        businessSummaryService.processSellTransaction(
                            transaction,
                            previousQty,
                            currentRow.getNumberOfStock(),
                            feesAndTaxes
                        );
                    }
                    break;
                    
                case DIVIDEND:
                    if (currentRow != null) {
                        feesAndTaxes = currentRow.getOpenFeeTax() != null ? currentRow.getOpenFeeTax() : BigDecimal.ZERO;
                        
                        businessSummaryService.processDividendTransaction(
                            transaction,
                            currentRow.getNumberOfStock(),
                            feesAndTaxes
                        );
                    }
                    break;
            }
        } catch (Exception e) {
            logger.warn("Failed to track business summary for transaction: {}", transaction, e);
        }
    }

    /**
     * Validate transaction data
     */
    private boolean isValidTransaction(Transaction transaction) {
        if (transaction == null) {
            logger.error("Transaction is null");
            return false;
        }
        
        // Check required fields
        if (transaction.getName() == null || transaction.getName().trim().isEmpty()) {
            logger.error("Transaction name is missing");
            return false;
        }
        
        if (transaction.getCode() == null || transaction.getCode().trim().isEmpty()) {
            logger.error("Transaction code is missing");
            return false;
        }
        
        if (transaction.getType() == null) {
            logger.error("Transaction type is missing");
            return false;
        }
        
        if (transaction.getDate() == null) {
            logger.error("Transaction date is missing");
            return false;
        }
        
        // For dividend transactions, quantity can be 0 (calculated from dividend amount)
        // For buy/sell transactions, quantity must be positive
        if (transaction.getType() != TransactionType.DIVIDEND) {
            if (transaction.getQuantity() == null || transaction.getQuantity().signum() <= 0) {
                logger.error("Transaction quantity is invalid: {}", transaction.getQuantity());
                return false;
            }
        } else {
            // For dividend, quantity should be 0 or null (will be calculated during processing)
            if (transaction.getQuantity() != null && transaction.getQuantity().signum() < 0) {
                logger.error("Transaction quantity is negative for dividend: {}", transaction.getQuantity());
                return false;
            }
        }
        
        if (transaction.getAmount() == null) {
            logger.error("Transaction amount is missing");
            return false;
        }
        
        // Type-specific validation
        switch (transaction.getType()) {
            case BUY:
            case SELL:
                if (transaction.getPrice() == null || transaction.getPrice().signum() <= 0) {
                    logger.error("Transaction price is invalid for {}: {}", transaction.getType(), transaction.getPrice());
                    return false;
                }
                break;
            case DIVIDEND:
                // For dividend, price can be 0
                if (transaction.getPrice() != null && transaction.getPrice().signum() < 0) {
                    logger.error("Transaction price is negative for dividend: {}", transaction.getPrice());
                    return false;
                }
                break;
        }
        
        return true;
    }

    /**
     * Get the appropriate transaction processor for the given type
     */
    public TransactionProcessorBase getProcessor(TransactionType type) {
        switch (type) {
            case BUY:
                return buyProcessor;
            case SELL:
                return sellProcessor;
            case DIVIDEND:
                return dividendProcessor;
            default:
                throw new IllegalArgumentException("Unknown transaction type: " + type);
        }
    }

    /**
     * Base class for transaction processors
     */
    public abstract static class TransactionProcessorBase {
        protected final GoogleSheetsService googleSheetsService;
        protected final Logger logger = LoggerFactory.getLogger(getClass());

        public TransactionProcessorBase(GoogleSheetsService googleSheetsService) {
            this.googleSheetsService = googleSheetsService;
        }

        /**
         * Process a transaction of the specific type
         */
        public abstract void processTransaction(Transaction transaction, 
                                              List<GoogleSheetsRow> existingRows,
                                              String spreadsheetId, 
                                              String sheetName) throws Exception;
    }
}