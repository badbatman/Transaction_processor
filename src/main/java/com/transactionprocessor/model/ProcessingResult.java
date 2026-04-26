package com.transactionprocessor.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the result of processing a batch of transactions
 */
public class ProcessingResult {
    private String sheetName;
    private LocalDateTime processingStartTime;
    private LocalDateTime processingEndTime;
    private int totalTransactions;
    private int successfulTransactions;
    private int failedTransactions;
    private List<Transaction> successfulTransactionsList;
    private List<FailedTransaction> failedTransactionsList;
    private int googleApiCalls;
    private String errorMessage;
    private boolean dryRun;
    private String backupSheetName;
    private List<Transaction> duplicateTransactions;

    public ProcessingResult(String sheetName) {
        this.sheetName = sheetName;
        this.processingStartTime = LocalDateTime.now();
        this.successfulTransactionsList = new ArrayList<>();
        this.failedTransactionsList = new ArrayList<>();
        this.duplicateTransactions = new ArrayList<>();
    }

    public void complete() {
        this.processingEndTime = LocalDateTime.now();
    }

    public void addSuccessfulTransaction(Transaction transaction) {
        successfulTransactionsList.add(transaction);
        successfulTransactions++;
    }

    public void addFailedTransaction(Transaction transaction, String errorMessage) {
        FailedTransaction failedTransaction = new FailedTransaction(transaction, errorMessage);
        failedTransactionsList.add(failedTransaction);
        failedTransactions++;
    }

    public void addDuplicateTransaction(Transaction transaction) {
        duplicateTransactions.add(transaction);
    }

    public void incrementApiCalls() {
        googleApiCalls++;
    }

    public void incrementApiCalls(int count) {
        googleApiCalls += count;
    }

    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public boolean isDryRun() { return dryRun; }

    public void setBackupSheetName(String name) { this.backupSheetName = name; }
    public String getBackupSheetName() { return backupSheetName; }

    // Getters
    public String getSheetName() { return sheetName; }
    public LocalDateTime getProcessingStartTime() { return processingStartTime; }
    public LocalDateTime getProcessingEndTime() { return processingEndTime; }
    public int getTotalTransactions() { return totalTransactions; }
    public int getSuccessfulTransactions() { return successfulTransactions; }
    public int getFailedTransactions() { return failedTransactions; }
    public List<Transaction> getSuccessfulTransactionsList() { return successfulTransactionsList; }
    public List<FailedTransaction> getFailedTransactionsList() { return failedTransactionsList; }
    public int getGoogleApiCalls() { return googleApiCalls; }
    public String getErrorMessage() { return errorMessage; }
    public List<Transaction> getDuplicateTransactions() { return duplicateTransactions; }
    public int getDuplicateTransactionsCount() { return duplicateTransactions.size(); }

    // Setters
    public void setTotalTransactions(int totalTransactions) { this.totalTransactions = totalTransactions; }
    public void setSuccessfulTransactions(int successfulTransactions) { this.successfulTransactions = successfulTransactions; }
    public void setFailedTransactions(int failedTransactions) { this.failedTransactions = failedTransactions; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    /**
     * Get processing time in milliseconds
     */
    public long getProcessingTimeMillis() {
        if (processingEndTime == null || processingStartTime == null) {
            return 0;
        }
        return java.time.Duration.between(processingStartTime, processingEndTime).toMillis();
    }

    /**
     * Get success rate as percentage
     */
    public double getSuccessRate() {
        if (totalTransactions == 0) return 0.0;
        return (double) successfulTransactions / totalTransactions * 100;
    }

    @Override
    public String toString() {
        return "ProcessingResult{" +
                "sheetName='" + sheetName + '\'' +
                ", totalTransactions=" + totalTransactions +
                ", successfulTransactions=" + successfulTransactions +
                ", failedTransactions=" + failedTransactions +
                ", duplicateTransactions=" + duplicateTransactions.size() +
                ", successRate=" + String.format("%.2f%%", getSuccessRate()) +
                ", processingTime=" + getProcessingTimeMillis() + "ms" +
                ", googleApiCalls=" + googleApiCalls +
                '}';
    }

    /**
     * Inner class to represent a failed transaction
     */
    public static class FailedTransaction {
        private final Transaction transaction;
        private final String errorMessage;
        private final LocalDateTime failureTime;

        public FailedTransaction(Transaction transaction, String errorMessage) {
            this.transaction = transaction;
            this.errorMessage = errorMessage;
            this.failureTime = LocalDateTime.now();
        }

        public Transaction getTransaction() { return transaction; }
        public String getErrorMessage() { return errorMessage; }
        public LocalDateTime getFailureTime() { return failureTime; }

        @Override
        public String toString() {
            return "FailedTransaction{" +
                    "transaction=" + transaction +
                    ", errorMessage='" + errorMessage + '\'' +
                    ", failureTime=" + failureTime +
                    '}';
        }
    }
}