package com.transactionprocessor.processor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.model.GoogleSheetsRow;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.service.TransactionProcessor.TransactionProcessorBase;

/**
 * Processor for dividend transactions
 * Handles dividend calculation and creates dividend income records
 */
public class DividendTransactionProcessor extends TransactionProcessorBase {
    private static final Logger logger = LoggerFactory.getLogger(DividendTransactionProcessor.class);
    
    // Pattern to extract dividend per 10 shares from description
    private static final Pattern DIVIDEND_PATTERN = Pattern.compile("每10股股息(\\d+\\.?\\d*)");
    
    public DividendTransactionProcessor(GoogleSheetsService googleSheetsService) {
        super(googleSheetsService);
    }

    @Override
    public void processTransaction(Transaction transaction,
                                 List<GoogleSheetsRow> existingRows,
                                 String spreadsheetId,
                                 String sheetName) throws Exception {

        logger.debug("Processing dividend transaction: {}", transaction);

        // REQUIREMENT.md Section 143: Check for duplicate with ALL five fields
        // Label (标的), Date (日期), Price (價錢 - dividend amount), Fees (稅費), Behavior (行為 - dividend)
        GoogleSheetsRow existingDuplicate = findExistingDividend(
            transaction.getLabel(),
            transaction.getDate(),
            calculateDividendAmount(transaction),
            transaction.calculateFee(),
            existingRows
        );

        if (existingDuplicate != null) {
            logger.warn("DUPLICATE DETECTED: Dividend transaction {} on {} with amount {}. Skipping to avoid duplicate entry.",
                       transaction.getLabel(), transaction.getDate(), calculateDividendAmount(transaction));
            // Skip this transaction - it already exists (Requirement.md Section 143)
            throw new DuplicateTransactionException("Duplicate dividend transaction: " + transaction);
        }

        // Parse dividend information
        DividendInfo dividendInfo = parseDividendInfo(transaction);

        if (dividendInfo == null) {
            throw new IllegalArgumentException("Unable to parse dividend information from transaction: " + transaction);
        }

        // REQUIREMENT.md Section 105-121: Implement proper row insertion logic
        // Same as buy transactions: find last valid row, check if next empty, insert if needed
        GoogleSheetsRow lastValidRow = findLastValidRow(transaction.getLabel(), existingRows);

        if (lastValidRow == null) {
            // No existing row for this label - append to end
            logger.debug("No existing row found for label {}, appending new dividend row to end", transaction.getLabel());
            createNewDividendRow(transaction, dividendInfo, existingRows, null);
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
                // Next row is empty - copy to next empty row
                logger.debug("Next row after last valid row {} is empty, copying dividend row to row {}",
                    lastValidRowNumber, nextRowNumber);
                copyDividendRowToNext(transaction, dividendInfo, existingRows, lastValidRow, nextRowNumber);
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
                // Now copy dividend row to the empty row we just inserted
                copyDividendRowToNext(transaction, dividendInfo, existingRows, lastValidRow, nextRowNumber);
            }
        }

        logger.debug("Dividend transaction processed successfully");
    }

    /**
     * Parse dividend information from transaction description
     */
    private DividendInfo parseDividendInfo(Transaction transaction) {
        if (transaction.getDescription() == null || transaction.getDescription().trim().isEmpty()) {
            logger.warn("No description found for dividend transaction");
            return null;
        }
        
        String description = transaction.getDescription().trim();
        Matcher matcher = DIVIDEND_PATTERN.matcher(description);
        
        if (!matcher.find()) {
            logger.warn("Unable to parse dividend per 10 shares from description: {}", description);
            return null;
        }
        
        try {
            BigDecimal dividendPer10Shares = new BigDecimal(matcher.group(1));
                    
            // Calculate fields as per updated requirements (lines 111-112, 118):
            // 原始股数量 = 1
            // 每股股息 = 派息總金額 (directly use transaction amount)
            BigDecimal originalShares = BigDecimal.ONE;
            BigDecimal dividendPerShare = transaction.getAmount(); // Total dividend amount
                    
            DividendInfo info = new DividendInfo();
            info.dividendPer10Shares = dividendPer10Shares; // Keep for reference
            info.dividendPerShare = dividendPerShare;
            info.originalShares = originalShares;
                    
            logger.debug("Parsed dividend info: per10Shares={}, perShare={}, originalShares={}", 
                dividendPer10Shares, dividendPerShare, originalShares);
                    
            return info;
            
        } catch (NumberFormatException | ArithmeticException e) {
            logger.error("Error parsing dividend amount from description: {}", description, e);
            return null;
        }
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
            if (label.equals(row.getLabel())) {
                return row;
            }
        }
        
        return null;
    }

    /**
     * Check if a dividend transaction with the same label, date, amount, and fee already exists
     * Requirement.md Section 143: 重複數據验证
     * For new dividend records, if existing data has the same label (标的), date (日期), 
     * dividend amount (價錢 - 派息總金額), fees (稅費), and behavior (行為 - dividend), 
     * do NOT perform any operation.
     */
    private GoogleSheetsRow findExistingDividend(String label, LocalDate date, BigDecimal dividendAmount,
                                                  BigDecimal fee, List<GoogleSheetsRow> existingRows) {
        if (label == null || date == null || dividendAmount == null || existingRows.isEmpty()) {
            return null;
        }

        // Normalize fee: treat null as ZERO for comparison
        BigDecimal normalizedFee = (fee == null) ? BigDecimal.ZERO : fee;

        // Search through all rows to find an exact match on ALL fields for dividend
        for (GoogleSheetsRow row : existingRows) {
            // Check label
            if (!label.equals(row.getLabel())) {
                continue;
            }

            // Check date (Open time)
            if (row.getOpenTime() == null || !row.getOpenTime().equals(date)) {
                continue;
            }

            // Check dividend amount (Close price - represents total dividend amount)
            if (row.getClosePrice() == null || row.getClosePrice().compareTo(dividendAmount) != 0) {
                continue;
            }

            // Check fee (Open Fee + Tax) - treat null as ZERO
            BigDecimal rowFee = (row.getOpenFeeTax() == null) ? BigDecimal.ZERO : row.getOpenFeeTax();
            if (rowFee.compareTo(normalizedFee) != 0) {
                continue;
            }

            // Check behavior - dividend transactions have specific pattern:
            // Open price = 0, Close time = Open time (same day)
            if (row.getOpenPrice() != null && row.getOpenPrice().compareTo(BigDecimal.ZERO) != 0) {
                continue;
            }

            logger.debug("Found existing dividend: {} on {} with amount {} at row {}",
                       label, date, dividendAmount, row.getRowNumber());
            return row;
        }

        return null;
    }

    /**
     * Calculate the dividend amount from transaction description
     * Format: "每 10 股股息 X.XX" means X.XX dividend per 10 shares
     */
    private BigDecimal calculateDividendAmount(Transaction transaction) {
        if (transaction.getDescription() == null || transaction.getDescription().trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        
        Matcher matcher = DIVIDEND_PATTERN.matcher(transaction.getDescription());
        if (matcher.find()) {
            try {
                String dividendPer10Shares = matcher.group(1);
                BigDecimal dividendPerShare = new BigDecimal(dividendPer10Shares).divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP);
                
                // Total dividend = dividend per share × quantity
                if (transaction.getQuantity() != null) {
                    return dividendPerShare.multiply(transaction.getQuantity()).setScale(2, RoundingMode.HALF_UP);
                }
                return dividendPerShare;
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse dividend amount from: {}", transaction.getDescription(), e);
            }
        }
        
        return BigDecimal.ZERO;
    }

    /**
     * Create a new dividend row when no existing rows are found
     */
    private void createNewDividendRow(Transaction transaction, DividendInfo dividendInfo, 
                                    List<GoogleSheetsRow> existingRows, GoogleSheetsRow templateRow) {
        logger.debug("Creating new dividend row for label: {}", transaction.getLabel());
        
        GoogleSheetsRow newRow = createDividendRow(transaction, dividendInfo, templateRow);
        // Don't set row number - will be handled by append operation
        // Mark as dirty so it gets written
        newRow.setDirty(true);
        existingRows.add(newRow);
        
        logger.debug("Added new dividend row to list, total rows: {}", existingRows.size());
    }


    /**
     * Create a new GoogleSheetsRow for a dividend transaction
     */
    private GoogleSheetsRow createDividendRow(Transaction transaction, DividendInfo dividendInfo, GoogleSheetsRow templateRow) {
        GoogleSheetsRow row = new GoogleSheetsRow();
        
        // If template row provided, copy ALL fields including formulas
        if (templateRow != null) {
            // Copy basic fields
            row.setLabel(templateRow.getLabel());
            row.setDomain(templateRow.getDomain());
            // Don't copy open/close times/prices as they'll be set by transaction
            row.setNumberOfStock(templateRow.getNumberOfStock());
            row.setOpenFeeTax(templateRow.getOpenFeeTax());
            row.setCloseTime(templateRow.getCloseTime());
            row.setClosePrice(templateRow.getClosePrice());
            row.setCloseFeeTax(templateRow.getCloseFeeTax());
            
            // Copy formula columns
            if (templateRow.getFormulaColumns() != null) {
                row.setFormulaColumns(new java.util.ArrayList<>(templateRow.getFormulaColumns()));
            }
            
            // Copy description and region
            row.setDescription(templateRow.getDescription());
            row.setRegion(templateRow.getRegion());
            row.setReserved(templateRow.getReserved());
        }
        
        // Override with dividend-specific data
        row.setLabel(transaction.getLabel());
        row.setOpenTime(transaction.getDate());
        row.setOpenPrice(BigDecimal.ZERO); // Dividend income has 0 open price
        // Original shares that received dividend
        row.setNumberOfStock(dividendInfo.originalShares);
        row.setOpenFeeTax(BigDecimal.ZERO); // No open fee for dividend

        // Set close information (dividend details)
        row.setCloseTime(transaction.getDate()); // Same as open time for dividend
        row.setClosePrice(dividendInfo.dividendPerShare); // Per-share dividend amount
        // REQUIREMENT.md Line 119: I列（Fee + Tax）：每次派息的稅費
        row.setCloseFeeTax(transaction.calculateFee()); // Record dividend tax/fee in close fee column
        
        // Set description to "派息"
        row.setDescription("派息");
        
        // Set region if not already set
        if (row.getRegion() == null || row.getRegion().isEmpty()) {
            row.setRegion(determineRegion(transaction.getCode()));
        }
        
        // Apply dividend transaction formatting (strikethrough + bold + italic)
        row.setStrikethrough(true);
        row.setBold(true);
        row.setItalic(true);
        row.setValid(false); // Dividend rows are marked as invalid (closed)
        row.setDirty(true); // Mark as modified so it gets written back
        
        return row;
    }

    /**
     * Copy dividend row to next empty row
     * @param nextRowNumber the Excel row number (1-based) where the row should be copied
     */
    private void copyDividendRowToNext(Transaction transaction, DividendInfo dividendInfo,
                                     List<GoogleSheetsRow> existingRows, GoogleSheetsRow lastValidRow,
                                     int nextRowNumber) {
        logger.debug("Copying dividend row to Excel row number: {} for label: {}",
                    nextRowNumber, transaction.getLabel());

        // Find if a row with this row number already exists
        GoogleSheetsRow existingRow = existingRows.stream()
            .filter(r -> r.getRowNumber() != null && r.getRowNumber() == nextRowNumber)
            .findFirst()
            .orElse(null);
        
        // Create the dividend row with copied data from template
        GoogleSheetsRow newRow = createDividendRow(transaction, dividendInfo, lastValidRow);
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

        logger.debug("Copied dividend row to Excel row number: {}", nextRowNumber);
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

    /**
     * Helper class to hold dividend information
     */
    private static class DividendInfo {
        BigDecimal dividendPer10Shares;
        BigDecimal dividendPerShare;
        BigDecimal originalShares;

        @Override
        public String toString() {
            return "DividendInfo{" +
                    "dividendPer10Shares=" + dividendPer10Shares +
                    ", dividendPerShare=" + dividendPerShare +
                    ", originalShares=" + originalShares +
                    '}';
        }
    }
}