package com.transactionprocessor.processor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.model.GoogleSheetsRow;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.service.TransactionProcessor.TransactionProcessorBase;

/**
 * Processor for sell transactions
 * Handles position matching, partial sells, and row splitting
 */
public class SellTransactionProcessor extends TransactionProcessorBase {
    private static final Logger logger = LoggerFactory.getLogger(SellTransactionProcessor.class);

    public SellTransactionProcessor(GoogleSheetsService googleSheetsService) {
        super(googleSheetsService);
    }

    @Override
    public void processTransaction(Transaction transaction,
                                 List<GoogleSheetsRow> existingRows,
                                 String spreadsheetId,
                                 String sheetName) throws Exception {

        logger.debug("Processing sell transaction: {}", transaction);

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
            logger.warn("DUPLICATE DETECTED: Sell transaction {} on {} at price {} with fee {}. Skipping to avoid duplicate entry.",
                       transaction.getLabel(), transaction.getDate(), transaction.getPrice(), transaction.calculateFee());
            // Skip this transaction - it already exists (Requirement.md Section 143)
            throw new DuplicateTransactionException("Duplicate sell transaction: " + transaction);
        }

        // Find matching positions for the sell
        List<PositionMatch> matches = findMatchingPositions(transaction, existingRows);

        if (matches.isEmpty()) {
            throw new IllegalStateException("No matching positions found for sell transaction: " + transaction.getLabel());
        }

        // Process the sell by updating the matched positions
        processSellMatches(transaction, matches, existingRows, spreadsheetId, sheetName);

        logger.debug("Sell transaction processed successfully");
    }

    /**
     * Find matching positions for a sell transaction
     * Priority: exact match > larger quantity > multiple smaller quantities
     */
    private List<PositionMatch> findMatchingPositions(Transaction sellTransaction, List<GoogleSheetsRow> existingRows) {
        List<PositionMatch> matches = new ArrayList<>();
        BigDecimal remainingQuantity = sellTransaction.getQuantity();
        
        logger.debug("Looking for positions matching label: '{}', quantity: {}", 
            sellTransaction.getLabel(), remainingQuantity);
        
        // Find all valid open positions for this label
        List<GoogleSheetsRow> openPositions = new ArrayList<>();
        for (GoogleSheetsRow row : existingRows) {
            if (row.isValid() && row.getLabel() != null) {
                // Normalize both labels for comparison (trim whitespace)
                String positionLabel = row.getLabel().trim();
                String transactionLabel = sellTransaction.getLabel().trim();
                
                logger.debug("Checking position: '{}' vs transaction: '{}' - match: {}", 
                    positionLabel, transactionLabel, positionLabel.equals(transactionLabel));
                
                if (positionLabel.equals(transactionLabel) &&
                    row.getCloseTime() == null && // Position is still open
                    row.getNumberOfStock() != null && row.getNumberOfStock().signum() > 0) {
                    openPositions.add(row);
                    logger.debug("Found valid open position: label={}, quantity={}, row={}", 
                        row.getLabel(), row.getNumberOfStock(), row.getRowNumber());
                }
            }
        }
        
        logger.info("Found {} open positions for label '{}'", openPositions.size(), sellTransaction.getLabel());
        
        if (openPositions.isEmpty()) {
            logger.warn("No open positions found for label: '{}'. Available labels:", sellTransaction.getLabel());
            for (GoogleSheetsRow row : existingRows) {
                if (row.getLabel() != null) {
                    logger.warn("  - '{}' (valid={}, closeTime={}, quantity={})", 
                        row.getLabel(), row.isValid(), row.getCloseTime(), row.getNumberOfStock());
                }
            }
            return matches;
        }
        
        // Sort by open price (ascending) for combining smaller positions as per requirements.
        // Fallback to quantity if open price is not available.
        openPositions.sort(Comparator.comparing(
            (GoogleSheetsRow r) -> r.getOpenPrice() != null ? r.getOpenPrice() : java.math.BigDecimal.ZERO
        ).thenComparing(GoogleSheetsRow::getNumberOfStock));
        
        // Try to find exact match first
        for (GoogleSheetsRow position : openPositions) {
            if (position.getNumberOfStock().compareTo(remainingQuantity) == 0) {
                matches.add(new PositionMatch(position, position.getNumberOfStock(), true));
                remainingQuantity = BigDecimal.ZERO;
                break;
            }
        }
        
        // If no exact match, try to find larger quantity
        if (remainingQuantity.signum() > 0) {
            for (GoogleSheetsRow position : openPositions) {
                if (position.getNumberOfStock().compareTo(remainingQuantity) > 0) {
                    matches.add(new PositionMatch(position, remainingQuantity, false));
                    remainingQuantity = BigDecimal.ZERO;
                    break;
                }
            }
        }
        
        // If still remaining, combine multiple smaller positions
        if (remainingQuantity.signum() > 0) {
            for (GoogleSheetsRow position : openPositions) {
                if (position.getNumberOfStock().compareTo(remainingQuantity) <= 0) {
                    matches.add(new PositionMatch(position, position.getNumberOfStock(), false));
                    remainingQuantity = remainingQuantity.subtract(position.getNumberOfStock());
                    
                    if (remainingQuantity.signum() <= 0) {
                        break;
                    }
                }
            }
        }
        
        // Check if we matched the full quantity
        if (remainingQuantity.signum() > 0) {
            logger.warn("Could not match full sell quantity. Remaining: {}", remainingQuantity);
            // Return partial matches anyway, but log warning
        }
        
        logger.debug("Found {} position matches for sell transaction", matches.size());
        return matches;
    }

    /**
     * Process the sell matches by updating the positions
     */
    private void processSellMatches(Transaction sellTransaction, 
                                  List<PositionMatch> matches,
                                  List<GoogleSheetsRow> existingRows,
                                  String spreadsheetId, 
                                  String sheetName) throws IOException {
        
        logger.debug("Processing {} sell matches", matches.size());
        
        BigDecimal totalFee = sellTransaction.calculateFee();
        boolean feeApplied = false; // Track if fee has been applied to first position

        for (int i = 0; i < matches.size(); i++) {
            PositionMatch match = matches.get(i);
            GoogleSheetsRow position = match.getPosition();

            logger.debug("Processing match {}: position quantity={}, sell quantity={}",
                i, position.getNumberOfStock(), match.getSellQuantity());

            BigDecimal feeToApply = BigDecimal.ZERO;

            // REQUIREMENT.md Lines 95-100: Fee allocation
            // "費用只計算一次在第一列或者原始列上. 不能重複計算"
            // Apply FULL fee to first position only, ZERO to all others
            if (!feeApplied && i == 0) {
                feeToApply = totalFee; // Full fee to first position
                feeApplied = true;
            }
            // All other positions get zero fee
            
            if (match.isExactMatch()) {
                // Exact match - close the entire position
                closePosition(sellTransaction, position, match.getSellQuantity(), feeToApply);
                
            } else {
                // Partial match - need to split the position
                if (match.getSellQuantity().compareTo(position.getNumberOfStock()) == 0) {
                    // Selling entire position
                    closePosition(sellTransaction, position, match.getSellQuantity(), feeToApply);
                } else {
                    // Selling part of position - split required
                    splitPosition(sellTransaction, position, match.getSellQuantity(),
                                existingRows, feeToApply);
                }
            }
        }
        
        // Apply formatting to all updated positions
        applySellFormatting(matches);
    }

    /**
     * Close a position completely
     */
    private void closePosition(Transaction sellTransaction, GoogleSheetsRow position, 
                             BigDecimal sellQuantity, BigDecimal fee) {
        
        logger.debug("Closing position completely: label={}, quantity={}", 
            position.getLabel(), sellQuantity);
        
        // Update close information
        position.setCloseTime(sellTransaction.getDate());
        position.setClosePrice(sellTransaction.getPrice());
        position.setCloseFeeTax(fee);
        
        // Update description with sell information (preserve existing + add new)
        String description = position.getDescription();
        if (sellTransaction.getRemarks() != null && !sellTransaction.getRemarks().trim().isEmpty()) {
            if (description != null && !description.trim().isEmpty()) {
                description += " " + sellTransaction.getRemarks();
            } else {
                description = sellTransaction.getRemarks();
            }
        }
        position.setDescription(description != null ? description : "");
        
        // Mark as closed (strikethrough + bold + italic)
        position.setStrikethrough(true);
        position.setBold(true);
        position.setItalic(true);
        position.setValid(false); // Closed positions are no longer valid
        position.setDirty(true); // Mark as modified so it gets written back
    }

    /**
     * Split a position when only partial quantity is sold
     */
    private void splitPosition(Transaction sellTransaction, GoogleSheetsRow position, 
                             BigDecimal sellQuantity, List<GoogleSheetsRow> existingRows,
                             BigDecimal fee) {
        
        logger.debug("Splitting position: original quantity={}, sell quantity={}", 
            position.getNumberOfStock(), sellQuantity);
        
        // Create new row for the sold portion (copy everything from original)
        GoogleSheetsRow soldRow = position.copy();
        soldRow.setNumberOfStock(sellQuantity);
        soldRow.setCloseTime(sellTransaction.getDate());
        soldRow.setClosePrice(sellTransaction.getPrice());
        soldRow.setCloseFeeTax(fee); // Fee only on the sold row
        
        // Update description (preserve existing + add new)
        String description = soldRow.getDescription();
        if (sellTransaction.getRemarks() != null && !sellTransaction.getRemarks().trim().isEmpty()) {
            if (description != null && !description.trim().isEmpty()) {
                description += " " + sellTransaction.getRemarks();
            } else {
                description = sellTransaction.getRemarks();
            }
        }
        soldRow.setDescription(description != null ? description : "");
        
        // Apply sell formatting
        soldRow.setStrikethrough(true);
        soldRow.setBold(true);
        soldRow.setItalic(true);
        soldRow.setValid(false);
        
        // Update original row for remaining quantity
        BigDecimal remainingQuantity = position.getNumberOfStock().subtract(sellQuantity);
        position.setNumberOfStock(remainingQuantity);
        // Reset close information for original row (it's still open)
        position.setCloseTime(null);
        position.setClosePrice(null);
        position.setCloseFeeTax(BigDecimal.ZERO); // No fee on remaining open position
        position.setDirty(true); // Mark as modified
        
        // Mark sold row as dirty too
        soldRow.setDirty(true);
        
        // Insert the sold row after the original position
        Integer positionRowNumber = position.getRowNumber();
        if (positionRowNumber == null) {
            logger.error("Position row number is null, cannot split position. Position: {}", position);
            return;
        }
        int soldRowNumber = positionRowNumber + 1;
        soldRow.setRowNumber(soldRowNumber);
        
        // Find the index where we should insert the sold row (after position in the list)
        int insertIndex = existingRows.indexOf(position) + 1;
        existingRows.add(insertIndex, soldRow);
        
        logger.debug("Split position created: sold row at Excel row number {} (list index {}), remaining quantity={}", 
            soldRowNumber, insertIndex, remainingQuantity);
    }

    /**
     * Apply sell formatting to all matched positions
     */
    private void applySellFormatting(List<PositionMatch> matches) {
        for (PositionMatch match : matches) {
            GoogleSheetsRow position = match.getPosition();
            
            // Apply strikethrough + bold + italic formatting
            position.setStrikethrough(true);
            position.setBold(true);
            position.setItalic(true);
            
            // If it's a partial match that wasn't split, mark as invalid
            if (!match.isExactMatch() && position.getNumberOfStock().compareTo(BigDecimal.ZERO) == 0) {
                position.setValid(false);
            }
            
            // Mark as dirty since we modified formatting
            position.setDirty(true);
        }
    }

    /**
     * Find an existing closed row that matches a sell transaction (duplicate detection)
     * Checks all 5 fields: label, date, price, fee, behavior
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

            // Check date (Close time for sell)
            if (row.getCloseTime() == null || !row.getCloseTime().equals(date)) {
                continue;
            }

            // Check price (Close price for sell)
            if (row.getClosePrice() == null || row.getClosePrice().compareTo(price) != 0) {
                continue;
            }

            // Check fee (Close Fee + Tax for sell) - treat null as ZERO
            BigDecimal rowFee = (row.getCloseFeeTax() == null) ? BigDecimal.ZERO : row.getCloseFeeTax();
            if (rowFee.compareTo(normalizedFee) != 0) {
                continue;
            }

            // Check behavior (type) - for sell transactions, verify it's marked as closed/sold
            // Sell transactions are identified by having close time/price
            if (row.getCloseTime() == null || row.getClosePrice() == null) {
                // This row is not closed, not a matching sell position
                continue;
            }

            logger.debug("Found existing sell transaction: {} on {} at price {} with fee {} at row {}",
                       label, date, price, normalizedFee, row.getRowNumber());
            return row;
        }

        return null;
    }

    /**
     * Helper class to represent a position match for selling
     */
    private static class PositionMatch {
        private final GoogleSheetsRow position;
        private final BigDecimal sellQuantity;
        private final boolean exactMatch;

        public PositionMatch(GoogleSheetsRow position, BigDecimal sellQuantity, boolean exactMatch) {
            this.position = position;
            this.sellQuantity = sellQuantity;
            this.exactMatch = exactMatch;
        }

        public GoogleSheetsRow getPosition() { return position; }
        public BigDecimal getSellQuantity() { return sellQuantity; }
        public boolean isExactMatch() { return exactMatch; }

        @Override
        public String toString() {
            return "PositionMatch{" +
                    "position=" + position.getLabel() +
                    ", sellQuantity=" + sellQuantity +
                    ", exactMatch=" + exactMatch +
                    '}';
        }
    }
}