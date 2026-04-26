package com.transactionprocessor.processor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.model.GoogleSheetsRow;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.service.TransactionProcessor.TransactionProcessorBase;

/**
 * Processor for buy transactions
 */
public class BuyTransactionProcessor extends TransactionProcessorBase {
    private static final Logger logger = LoggerFactory.getLogger(BuyTransactionProcessor.class);

    public BuyTransactionProcessor(GoogleSheetsService googleSheetsService) {
        super(googleSheetsService);
    }

    @Override
    public void processTransaction(Transaction transaction,
                                 List<GoogleSheetsRow> existingRows,
                                 String spreadsheetId,
                                 String sheetName) throws Exception {

        logger.debug("Processing buy transaction: {}", transaction);

        // REQUIREMENT.md Section 143: Check for duplicate with ALL five fields
        // Label (标的), Date (日期), Price (價錢), Fees (稅費), Behavior (行為 - buy/sell/dividend)
        GoogleSheetsRow existingDuplicate = findExistingTransaction(
            transaction.getLabel(),
            transaction.getDate(),
            transaction.getPrice(),
            transaction.calculateFee(),
            transaction.getType(),
            existingRows
        );

        if (existingDuplicate != null) {
            logger.warn("DUPLICATE DETECTED: Buy transaction {} on {} at price {} with fee {}. Skipping to avoid duplicate entry.",
                       transaction.getLabel(), transaction.getDate(), transaction.getPrice(), transaction.calculateFee());
            // Skip this transaction - it already exists (Requirement.md Section 143)
            throw new DuplicateTransactionException("Duplicate buy transaction: " + transaction);
        }

        // REQUIREMENT.md Section 74-82: Implement proper row insertion logic
        // 1. Find last valid row for the label
        // 2. Check if next row is empty
        // 3. If empty: copy to next empty row
        // 4. If not empty: insert new empty row, then copy
        GoogleSheetsRow lastValidRow = findLastValidRow(transaction.getLabel(), existingRows);

        if (lastValidRow == null) {
            // No existing row for this label - append to end
            logger.debug("No existing row found for label {}, appending new buy row to end", transaction.getLabel());
            createNewBuyRow(transaction, existingRows, null);
        } else {
            // Found existing row - check if next Excel row is empty
            int lastValidRowNumber = lastValidRow.getRowNumber();
            int nextRowNumber = lastValidRowNumber + 1;
            
            // Find the row with the next row number in the existing rows list
            GoogleSheetsRow nextRow = existingRows.stream()
                .filter(r -> r.getRowNumber() != null && r.getRowNumber() == nextRowNumber)
                .findFirst()
                .orElse(null);
            
            boolean nextRowIsEmpty = (nextRow == null) || isRowEmpty(nextRow);
            
            if (nextRowIsEmpty) {
                // Next row is empty or doesn't exist - copy to next row
                logger.debug("Next row after last valid row {} is empty, copying buy row to row {}",
                    lastValidRowNumber, nextRowNumber);
                copyBuyRowToNext(transaction, existingRows, lastValidRow, nextRowNumber);
            } else {
                // Next row is not empty - insert new empty row first
                logger.debug("Next row after last valid row {} is occupied, inserting new empty row and copying",
                    lastValidRowNumber);
                // Find the index where we need to insert
                int insertIndex = existingRows.indexOf(nextRow);
                // Insert empty row at this position
                GoogleSheetsRow emptyRow = new GoogleSheetsRow();
                emptyRow.setRowNumber(nextRowNumber);
                emptyRow.setValid(true);
                existingRows.add(insertIndex, emptyRow);
                // Now copy buy row to the empty row at the same index
                copyBuyRowToNext(transaction, existingRows, lastValidRow, nextRowNumber);
            }
        }

        logger.debug("Buy transaction processed successfully");
    }

    /**
     * Find the last valid row for the given label
     */
    private GoogleSheetsRow findLastValidRow(String label, List<GoogleSheetsRow> existingRows) {
        if (label == null || existingRows.isEmpty()) {
            return null;
        }

        // Search from the end to find the last valid row
        for (int i = existingRows.size() - 1; i >= 0; i--) {
            GoogleSheetsRow row = existingRows.get(i);
            if (row.isValid() && label.equals(row.getLabel())) {
                return row;
            }
        }
        
        return null;
    }

    /**
     * Check if a transaction with the same label, date, price, fee, and type already exists
     * Requirement.md Section 143: 重複數據验证
     * For new transaction records, if existing data has the same label (标的), date (日期), 
     * price (價錢), fees (稅費), and behavior (行為 - buy/sell/dividend), do NOT perform any operation.
     */
    private GoogleSheetsRow findExistingTransaction(String label, LocalDate date, BigDecimal price,
                                                    BigDecimal fee, TransactionType type,
                                                    List<GoogleSheetsRow> existingRows) {
        if (label == null || date == null || price == null || existingRows.isEmpty()) {
            return null;
        }

        // Normalize fee: treat null as ZERO for comparison
        BigDecimal normalizedFee = (fee == null) ? BigDecimal.ZERO : fee;

        // Search through all rows to find an exact match on ALL five fields
        for (GoogleSheetsRow row : existingRows) {
            // Check label
            if (!label.equals(row.getLabel())) {
                continue;
            }

            // Check date (Open time)
            if (row.getOpenTime() == null || !row.getOpenTime().equals(date)) {
                continue;
            }

            // Check price (Open price)
            if (row.getOpenPrice() == null || row.getOpenPrice().compareTo(price) != 0) {
                continue;
            }

            // Check fee (Open Fee + Tax) - treat null as ZERO
            BigDecimal rowFee = (row.getOpenFeeTax() == null) ? BigDecimal.ZERO : row.getOpenFeeTax();
            if (rowFee.compareTo(normalizedFee) != 0) {
                continue;
            }

            // Check behavior (type) - for buy transactions, verify it's marked as buy
            // Buy transactions are identified by having open time/price but no close time
            if (row.getCloseTime() != null) {
                // This row has been closed, not a matching buy position
                continue;
            }

            logger.debug("Found existing transaction: {} on {} at price {} with fee {} at row {}",
                       label, date, price, normalizedFee, row.getRowNumber());
            return row;
        }

        return null;
    }

    /**
     * Create a new buy row when no existing rows are found
     */
    private void createNewBuyRow(Transaction transaction, List<GoogleSheetsRow> existingRows, GoogleSheetsRow templateRow) {
        logger.debug("Creating new buy row for label: {}", transaction.getLabel());
        
        GoogleSheetsRow newRow = createBuyRow(transaction, templateRow);
        // Assign row number: append to end means next row number after the last row
        // Find the last row with a non-null row number
        int nextRowNumber = 2;
        if (!existingRows.isEmpty()) {
            for (int i = existingRows.size() - 1; i >= 0; i--) {
                GoogleSheetsRow row = existingRows.get(i);
                if (row.getRowNumber() != null) {
                    nextRowNumber = row.getRowNumber() + 1;
                    break;
                }
            }
        }
        newRow.setRowNumber(nextRowNumber);
        newRow.setDirty(true);
        existingRows.add(newRow);
        
        logger.debug("Added new buy row to list at row number: {}, total rows: {}", nextRowNumber, existingRows.size());
    }

    /**
     * Add a buy row after the specified existing row
     */
    private void addBuyRowAfter(Transaction transaction, List<GoogleSheetsRow> existingRows, GoogleSheetsRow lastValidRow) {
        int nextRowNumber = lastValidRow.getRowNumber() + 1;
        int insertIndex = existingRows.indexOf(lastValidRow) + 1;
        
        logger.debug("Adding buy row after row {} (next row number: {}) for label: {}", 
            lastValidRow.getRowNumber(), nextRowNumber, transaction.getLabel());
        
        // Create the new buy row, copying ALL data from the last valid row
        GoogleSheetsRow newRow = createBuyRow(transaction, lastValidRow);
        // Set row number based on Excel row number, not list index
        newRow.setRowNumber(nextRowNumber);
        
        // Insert at the appropriate position
        existingRows.add(insertIndex, newRow);
        
        logger.debug("Added buy row at Excel row number: {}", nextRowNumber);
    }

    /**
     * Create a new GoogleSheetsRow for a buy transaction
     */
    private GoogleSheetsRow createBuyRow(Transaction transaction, GoogleSheetsRow templateRow) {
        GoogleSheetsRow row = new GoogleSheetsRow();
        
        // If template row provided, copy ALL fields including formulas
        if (templateRow != null) {
            // Copy all basic fields from template
            row.setLabel(templateRow.getLabel());
            row.setDomain(templateRow.getDomain());
            row.setOpenTime(templateRow.getOpenTime());
            row.setOpenPrice(templateRow.getOpenPrice());
            row.setNumberOfStock(templateRow.getNumberOfStock());
            row.setOpenFeeTax(templateRow.getOpenFeeTax());
            row.setCloseTime(templateRow.getCloseTime());
            row.setClosePrice(templateRow.getClosePrice());
            row.setCloseFeeTax(templateRow.getCloseFeeTax());
            
            // Copy formula columns
            if (templateRow.getFormulaColumns() != null) {
                row.setFormulaColumns(new java.util.ArrayList<>(templateRow.getFormulaColumns()));
            }
            

            row.setRegion(templateRow.getRegion());
            row.setReserved(templateRow.getReserved());
        }
        
        // Override with transaction-specific data
        row.setLabel(transaction.getLabel());
        row.setOpenTime(transaction.getDate());
        row.setOpenPrice(transaction.getPrice());
        row.setNumberOfStock(transaction.getQuantity());
        row.setOpenFeeTax(transaction.calculateFee());
        
        // Set description to transaction remarks only (not combining with template description)
        String description = (transaction.getRemarks() != null && !transaction.getRemarks().trim().isEmpty()) 
            ? transaction.getRemarks() 
            : "";
        row.setDescription(description);
        
        // Set region if not already set
        if (row.getRegion() == null || row.getRegion().isEmpty()) {
            row.setRegion(determineRegion(transaction.getCode()));
        }
        
        // Apply buy transaction formatting (bold + italic)
        row.setBold(true);
        row.setItalic(true);
        row.setStrikethrough(false);
        row.setValid(true);
        // NOTE: Row number is NOT set here - it must be set by the caller based on context
        // (append vs insert, and what rows already exist)
        row.setDirty(true); // Mark as modified so it gets written back
        
        return row;
    }

    /**
     * Copy buy row data to the next row (which should be empty)
     * @param nextRowNumber the Excel row number (1-based) where the row should be copied
     */
    private void copyBuyRowToNext(Transaction transaction, List<GoogleSheetsRow> existingRows,
                                GoogleSheetsRow lastValidRow, int nextRowNumber) {
        logger.debug("Copying buy row to Excel row number: {} for label: {}",
                    nextRowNumber, transaction.getLabel());

        // Find if a row with this row number already exists
        GoogleSheetsRow existingRow = existingRows.stream()
            .filter(r -> r.getRowNumber() != null && r.getRowNumber() == nextRowNumber)
            .findFirst()
            .orElse(null);
        
        // Create the new buy row with copied data from template
        GoogleSheetsRow newRow = createBuyRow(transaction, lastValidRow);
        newRow.setRowNumber(nextRowNumber);
        newRow.setDirty(true);
        
        if (existingRow != null) {
            // Replace existing row at its current index
            int existingIndex = existingRows.indexOf(existingRow);
            existingRows.set(existingIndex, newRow);
            logger.debug("Replaced existing row at index: {}", existingIndex);
        } else {
            // Find the correct position to insert based on row number
            int insertIndex = 0;
            for (int i = 0; i < existingRows.size(); i++) {
                if (existingRows.get(i).getRowNumber() != null && existingRows.get(i).getRowNumber() < nextRowNumber) {
                    insertIndex = i + 1;
                }
            }
            existingRows.add(insertIndex, newRow);
            logger.debug("Inserted new row at index: {}", insertIndex);
        }

        logger.debug("Copied buy row to Excel row number: {}", nextRowNumber);
    }
    
    /**
     * Check if a row is empty (no meaningful data)
     */
    private boolean isRowEmpty(GoogleSheetsRow row) {
        if (row == null) return true;
        
        // Check if basic fields are empty
        boolean hasLabel = row.getLabel() != null && !row.getLabel().trim().isEmpty();
        boolean hasData = row.getOpenTime() != null || 
                         row.getOpenPrice() != null || 
                         row.getNumberOfStock() != null ||
                         row.getCloseTime() != null ||
                         row.getClosePrice() != null;
        
        return !hasLabel && !hasData;
    }
    
    /**
     * Determine region based on stock code
     */
    private String determineRegion(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "";
        }
        
        String upperCode = code.toUpperCase();
        
        if (upperCode.startsWith("SH") || upperCode.startsWith("SZ")) {
            return "A股";
        } else if (upperCode.startsWith("HKG:") || upperCode.matches("\\d{4}")) {
            return "港股";
        } else if (upperCode.length() <= 5 && upperCode.matches("[A-Z]+")) {
            return "美股";
        } else {
            return "其他";
        }
    }
}