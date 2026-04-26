package com.transactionprocessor.processor;

import com.transactionprocessor.model.Transaction;

/**
 * Exception thrown when a duplicate transaction is detected
 * According to Requirement.md Section 143:
 * For new transaction records, if existing data has the same label, date, 
 * price, fees, and behavior (buy/sell/dividend), do NOT perform any operation.
 */
public class DuplicateTransactionException extends Exception {
    
    private final Transaction duplicateTransaction;
    
    public DuplicateTransactionException(String message) {
        super(message);
        this.duplicateTransaction = null;
    }
    
    public DuplicateTransactionException(String message, Transaction transaction) {
        super(message);
        this.duplicateTransaction = transaction;
    }
    
    public Transaction getDuplicateTransaction() {
        return duplicateTransaction;
    }
}
