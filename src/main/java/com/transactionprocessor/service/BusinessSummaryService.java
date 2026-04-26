package com.transactionprocessor.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.model.Transaction;

/**
 * Simplified Business Summary Service - Focused on current transaction operations
 */
public class BusinessSummaryService {
    private static final Logger logger = LoggerFactory.getLogger(BusinessSummaryService.class);
    
    // Transaction counters
    private final AtomicInteger buyCount = new AtomicInteger(0);
    private final AtomicInteger sellCount = new AtomicInteger(0);
    private final AtomicInteger dividendCount = new AtomicInteger(0);
    
    // Position changes
    private final AtomicInteger newPositionsOpened = new AtomicInteger(0);
    private final AtomicInteger positionsIncreased = new AtomicInteger(0);
    private final AtomicInteger positionsDecreased = new AtomicInteger(0);
    private final AtomicInteger positionsClosed = new AtomicInteger(0);
    private final AtomicInteger dividendsReceived = new AtomicInteger(0);
    
    // Financial totals
    private final AtomicReference<BigDecimal> totalInvested = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalReturned = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalDividends = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalFeesAndTaxes = new AtomicReference<>(BigDecimal.ZERO);
    
    // Transaction details for reporting
    private final List<TransactionDetail> transactionDetails = new ArrayList<>();
    private final List<SkippedTransaction> skippedTransactions = new ArrayList<>();
    
    /**
     * Record of a skipped transaction
     */
    public static class SkippedTransaction {
        private final String reason;
        private final Transaction transaction;
        
        public SkippedTransaction(String reason, Transaction transaction) {
            this.reason = reason;
            this.transaction = transaction;
        }
        
        public String getReason() { return reason; }
        public Transaction getTransaction() { return transaction; }
    }
    
    /**
     * Track a skipped transaction
     */
    public void trackSkippedTransaction(String reason, Transaction transaction) {
        skippedTransactions.add(new SkippedTransaction(reason, transaction));
        logger.info("⏭️  SKIPPED | {} | {}", transaction.getName(), reason);
    }
    
    /**
     * Simple transaction detail record
     */
    public static class TransactionDetail {
        private final String date;
        private final LocalDate transactionDate;
        private final String action; // OPENED, INCREASED, DECREASED, CLOSED, DIVIDEND
        private final String symbol;
        private final String details;
        private final BigDecimal feesAndTaxes;
        private final boolean skipped;
        
        public TransactionDetail(String action, String symbol, String details, LocalDate transactionDate, BigDecimal feesAndTaxes) {
            this.transactionDate = transactionDate;
            this.date = transactionDate != null ? transactionDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "N/A";
            this.action = action;
            this.symbol = symbol;
            this.details = details;
            this.feesAndTaxes = feesAndTaxes != null ? feesAndTaxes : BigDecimal.ZERO;
            this.skipped = false;
        }
        
        public TransactionDetail(String action, String symbol, String details, boolean skipped) {
            this.date = "N/A";
            this.transactionDate = null;
            this.action = action;
            this.symbol = symbol;
            this.details = details;
            this.feesAndTaxes = BigDecimal.ZERO;
            this.skipped = skipped;
        }
        
        public String getDate() { return date; }
        public LocalDate getTransactionDate() { return transactionDate; }
        public String getAction() { return action; }
        public String getSymbol() { return symbol; }
        public String getDetails() { return details; }
        public BigDecimal getFeesAndTaxes() { return feesAndTaxes; }
        public boolean isSkipped() { return skipped; }
    }
    
    /**
     * Process a buy transaction
     */
    public void processBuyTransaction(Transaction transaction, 
                                     BigDecimal previousQuantity,
                                     BigDecimal newQuantity,
                                     BigDecimal feesAndTaxes) {
        buyCount.incrementAndGet();
        
        if (previousQuantity.compareTo(BigDecimal.ZERO) == 0) {
            newPositionsOpened.incrementAndGet();
            
            String icon = "➕";
            BigDecimal totalCost = transaction.getAmount().add(feesAndTaxes);
            String detail = String.format("Bought %s @ HK$%s = HK$%s + Fees HK$%s = Total HK$%s",
                formatNumber(transaction.getQuantity()),
                formatNumber(transaction.getPrice()),
                formatCurrency(transaction.getAmount()),
                formatCurrency(feesAndTaxes),
                formatCurrency(totalCost));
            
            transactionDetails.add(new TransactionDetail("OPENED", transaction.getName(), detail, transaction.getDate(), feesAndTaxes));
            logger.info("{} {} | {}", icon, transaction.getName(), detail);
        } else {
            positionsIncreased.incrementAndGet();
            
            String icon = "📈";
            BigDecimal totalCost = transaction.getAmount().add(feesAndTaxes);
            String detail = String.format("Added %s shares @ HK$%s = HK$%s + Fees HK$%s = Total HK$%s",
                formatNumber(transaction.getQuantity()),
                formatNumber(transaction.getPrice()),
                formatCurrency(transaction.getAmount()),
                formatCurrency(feesAndTaxes),
                formatCurrency(totalCost));
            
            transactionDetails.add(new TransactionDetail("INCREASED", transaction.getName(), detail, transaction.getDate(), feesAndTaxes));
            logger.info("{} {} | {}", icon, transaction.getName(), detail);
        }
        
        totalInvested.updateAndGet(v -> v.add(transaction.getAmount()));
        totalFeesAndTaxes.updateAndGet(v -> v.add(feesAndTaxes));
    }
    
    /**
     * Process a sell transaction
     */
    public void processSellTransaction(Transaction transaction,
                                      BigDecimal previousQuantity,
                                      BigDecimal newQuantity,
                                      BigDecimal feesAndTaxes) {
        sellCount.incrementAndGet();
        
        if (newQuantity.compareTo(BigDecimal.ZERO) == 0) {
            positionsClosed.incrementAndGet();
            
            String icon = "✅";
            BigDecimal netProceeds = transaction.getAmount().subtract(feesAndTaxes);
            String detail = String.format("Sold all %s shares @ HK$%s = HK$%s - Fees HK$%s = Net HK$%s",
                formatNumber(transaction.getQuantity()),
                formatNumber(transaction.getPrice()),
                formatCurrency(transaction.getAmount()),
                formatCurrency(feesAndTaxes),
                formatCurrency(netProceeds));
            
            transactionDetails.add(new TransactionDetail("CLOSED", transaction.getName(), detail, transaction.getDate(), feesAndTaxes));
            logger.info("{} {} | {}", icon, transaction.getName(), detail);
        } else {
            positionsDecreased.incrementAndGet();
            
            String icon = "📉";
            BigDecimal netProceeds = transaction.getAmount().subtract(feesAndTaxes);
            String detail = String.format("Sold %s shares @ HK$%s = HK$%s - Fees HK$%s = Net HK$%s",
                formatNumber(transaction.getQuantity()),
                formatNumber(transaction.getPrice()),
                formatCurrency(transaction.getAmount()),
                formatCurrency(feesAndTaxes),
                formatCurrency(netProceeds));
            
            transactionDetails.add(new TransactionDetail("DECREASED", transaction.getName(), detail, transaction.getDate(), feesAndTaxes));
            logger.info("{} {} | {}", icon, transaction.getName(), detail);
        }
        
        totalReturned.updateAndGet(v -> v.add(transaction.getAmount()));
        totalFeesAndTaxes.updateAndGet(v -> v.add(feesAndTaxes));
    }
    
    /**
     * Process a dividend transaction
     */
    public void processDividendTransaction(Transaction transaction,
                                          BigDecimal currentQuantity,
                                          BigDecimal feesAndTaxes) {
        dividendCount.incrementAndGet();
        dividendsReceived.incrementAndGet();
        
        String icon = "💰";
        String description = transaction.getDescription() != null ? transaction.getDescription() : "";
        String detail = String.format("Received HK$%s for %s shares%s (Net after fees: HK$%s)",
            formatCurrency(transaction.getAmount()),
            formatNumber(currentQuantity),
            description.isEmpty() ? "" : " (" + description + ")",
            formatCurrency(transaction.getAmount().subtract(feesAndTaxes)));
        
        transactionDetails.add(new TransactionDetail("DIVIDEND", transaction.getName(), detail, transaction.getDate(), feesAndTaxes));
        logger.info("{} {} | {}", icon, transaction.getName(), detail);
        
        totalDividends.updateAndGet(v -> v.add(transaction.getAmount()));
        totalFeesAndTaxes.updateAndGet(v -> v.add(feesAndTaxes));
    }
    
    /**
     * Generate simplified summary report
     */
    public String generateSummaryReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("=" .repeat(80)).append("\n");
        report.append("TRANSACTION PROCESSING SUMMARY\n");
        report.append("Processing Date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        report.append("=" .repeat(80)).append("\n\n");
        
        // Transaction Statistics
        report.append("📊 TRANSACTION STATISTICS\n");
        report.append("-".repeat(80)).append("\n");
        report.append(String.format("Total Transactions Processed:  %d\n", getTotalTransactions()));
        report.append(String.format("├─ Buy Transactions:           %d\n", buyCount.get()));
        report.append(String.format("├─ Sell Transactions:          %d\n", sellCount.get()));
        report.append(String.format("└─ Dividend Transactions:      %d\n\n", dividendCount.get()));
        
        report.append("Position Changes:\n");
        report.append(String.format("├─ New Positions Opened:       %d\n", newPositionsOpened.get()));
        report.append(String.format("├─ Positions Increased:        %d\n", positionsIncreased.get()));
        report.append(String.format("├─ Positions Decreased:        %d\n", positionsDecreased.get()));
        report.append(String.format("├─ Positions Closed:           %d\n", positionsClosed.get()));
        report.append(String.format("└─ Dividends Received:         %d\n\n", dividendsReceived.get()));
        
        // Financial Summary
        report.append("💵 FINANCIAL SUMMARY\n");
        report.append("-".repeat(80)).append("\n");
        report.append(String.format("Capital Deployed:              HK$ %s\n", formatCurrency(totalInvested.get())));
        report.append(String.format("Capital Returned:              HK$ %s\n", formatCurrency(totalReturned.get())));
        report.append(String.format("Dividend Income:               HK$ %s\n", formatCurrency(totalDividends.get())));
        report.append(String.format("Transaction Costs:             HK$ %s\n", formatCurrency(totalFeesAndTaxes.get())));
        report.append("-".repeat(80)).append("\n");
        
        BigDecimal netCashFlow = totalReturned.get().subtract(totalInvested.get());
        report.append(String.format("Net Cash Flow:                 HK$ %s\n", formatCurrency(netCashFlow)));
        
        if (totalInvested.get().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal returnRate = netCashFlow.divide(totalInvested.get(), 4, RoundingMode.HALF_UP)
                                              .multiply(new BigDecimal("100"));
            report.append(String.format("Return Rate:                   %.2f%%\n\n", returnRate.doubleValue()));
        } else {
            report.append("\n");
        }
        
        // Transaction Details
        report.append("✅ TRANSACTION DETAILS\n");
        report.append("-".repeat(80)).append("\n");
        report.append(String.format("%-12s | %-8s | %-25s | %s\n", "DATE", "ACTION", "SYMBOL", "DETAILS"));
        report.append("-".repeat(80)).append("\n");
        
        for (TransactionDetail detail : transactionDetails) {
            String icon = switch (detail.getAction()) {
                case "OPENED" -> "➕";
                case "INCREASED" -> "📈";
                case "DECREASED" -> "📉";
                case "CLOSED" -> "✅";
                case "DIVIDEND" -> "💰";
                default -> "•";
            };
            
            report.append(String.format("%-12s | %s %-7s | %-25s | %s\n",
                                       detail.getDate(),
                                       icon,
                                       detail.getAction(),
                                       detail.getSymbol(),
                                       detail.getDetails()));
        }
        
        // Skipped Transactions
        if (!skippedTransactions.isEmpty()) {
            report.append("\n");
            report.append("⏭️  SKIPPED TRANSACTIONS\n");
            report.append("-".repeat(80)).append("\n");
            report.append(String.format("%-8s | %-25s | %s\n", "LINE", "SYMBOL", "REASON"));
            report.append("-".repeat(80)).append("\n");
            
            for (SkippedTransaction skipped : skippedTransactions) {
                Transaction tx = skipped.getTransaction();
                report.append(String.format("%-8d | %-25s | %s\n",
                                           tx.getLineNumber(),
                                           tx.getName(),
                                           skipped.getReason()));
            }
        }
        
        report.append("\n").append("=" .repeat(80)).append("\n");
        report.append("END OF SUMMARY\n");
        report.append("=" .repeat(80));
        
        return report.toString();
    }
    
    /**
     * Log summary to logger
     */
    public void logSummary() {
        logger.info("=" .repeat(80));
        logger.info("TRANSACTION PROCESSING SUMMARY");
        logger.info("=" .repeat(80));
        logger.info("📊 Total Transactions: {}", getTotalTransactions());
        logger.info("   ├─ Buy: {}, Sell: {}, Dividend: {}", buyCount.get(), sellCount.get(), dividendCount.get());
        logger.info("");
        logger.info("Position Changes:");
        logger.info("   ├─ Opened: {}, Increased: {}, Decreased: {}, Closed: {}",
                   newPositionsOpened.get(), positionsIncreased.get(), positionsDecreased.get(), positionsClosed.get());
        logger.info("   └─ Dividends: {}", dividendsReceived.get());
        logger.info("");
        logger.info("💵 Financial Summary:");
        logger.info("   ├─ Invested: HK$ {}, Returned: HK$ {}", 
                   formatCurrency(totalInvested.get()), formatCurrency(totalReturned.get()));
        logger.info("   ├─ Dividends: HK$ {}, Costs: HK$ {}",
                   formatCurrency(totalDividends.get()), formatCurrency(totalFeesAndTaxes.get()));
        
        BigDecimal netCashFlow = totalReturned.get().subtract(totalInvested.get());
        logger.info("   └─ Net Cash Flow: HK$ {}", formatCurrency(netCashFlow));
        
        if (totalInvested.get().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal returnRate = netCashFlow.divide(totalInvested.get(), 4, RoundingMode.HALF_UP)
                                              .multiply(new BigDecimal("100"));
            logger.info("   └─ Return Rate: {:.2f}%", returnRate.doubleValue());
        }
        
        if (!skippedTransactions.isEmpty()) {
            logger.info("");
            logger.info("⏭️  Skipped Transactions: {}", skippedTransactions.size());
            for (SkippedTransaction skipped : skippedTransactions) {
                logger.info("   └─ Line {}: {} - {}", 
                           skipped.getTransaction().getLineNumber(),
                           skipped.getTransaction().getName(),
                           skipped.getReason());
            }
        }
        
        logger.info("=" .repeat(80));
    }
    
    /**
     * Format currency value
     */
    private String formatCurrency(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) {
            return "0.00";
        }
        return String.format("%,.2f", value.doubleValue());
    }
    
    /**
     * Format number with commas
     */
    private String formatNumber(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        // Preserve decimal places for prices and quantities
        if (value.scale() > 0) {
            return String.format("%,.2f", value.doubleValue());
        }
        return String.format("%,d", value.longValue());
    }
    
    /**
     * Get total transactions
     */
    private int getTotalTransactions() {
        return buyCount.get() + sellCount.get() + dividendCount.get();
    }
    
    /**
     * Reset all statistics for new run
     */
    public void reset() {
        buyCount.set(0);
        sellCount.set(0);
        dividendCount.set(0);
        newPositionsOpened.set(0);
        positionsIncreased.set(0);
        positionsDecreased.set(0);
        positionsClosed.set(0);
        dividendsReceived.set(0);
        totalInvested.set(BigDecimal.ZERO);
        totalReturned.set(BigDecimal.ZERO);
        totalDividends.set(BigDecimal.ZERO);
        totalFeesAndTaxes.set(BigDecimal.ZERO);
        transactionDetails.clear();
    }
}
