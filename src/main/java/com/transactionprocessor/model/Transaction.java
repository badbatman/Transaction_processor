package com.transactionprocessor.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a single transaction record from CSV file
 */
public class Transaction {
    private String name;
    private String code;
    private TransactionType type;
    private LocalDate date;
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal amount;
    private String description;
    private String remarks;
    private String rawLine; // Original CSV line for error reporting
    private int lineNumber; // Line number in CSV file

    public Transaction() {}

    public Transaction(String name, String code, TransactionType type, LocalDate date, 
                      BigDecimal price, BigDecimal quantity, BigDecimal amount, 
                      String description, String remarks) {
        this.name = name;
        this.code = code;
        this.type = type;
        this.date = date;
        this.price = price;
        this.quantity = quantity;
        this.amount = amount;
        this.description = description;
        this.remarks = remarks;
    }

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public String getRawLine() { return rawLine; }
    public void setRawLine(String rawLine) { this.rawLine = rawLine; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    /**
     * Get the fee from remarks if present
     * Format: fee=123.45 in the remarks field
     */
    public BigDecimal getFeeFromRemarks() {
        if (remarks == null || remarks.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        
        // Extract fee from remarks using regex pattern: fee=^\d+(?:\.\d+)?$
        String[] parts = remarks.trim().split("\\s+");
        for (String part : parts) {
            if (part.toLowerCase().startsWith("fee=")) {
                try {
                    String feeValue = part.substring(4).trim();
                    return new BigDecimal(feeValue);
                } catch (NumberFormatException e) {
                    // Silently return zero on parse error
                    return BigDecimal.ZERO;
                }
            }
        }
        
        return BigDecimal.ZERO;
    }
    
    /**
     * Calculate fee + tax for the transaction
     * For backward compatibility - returns fee from remarks
     */
    public BigDecimal calculateFee() {
        return getFeeFromRemarks();
    }

    /**
     * Get the label for this transaction (code mapped to readable name)
     */
    public String getLabel() {
        return name; // This will be updated by CodeLabelMappingService
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transaction that = (Transaction) o;
        return lineNumber == that.lineNumber &&
                Objects.equals(name, that.name) &&
                Objects.equals(code, that.code) &&
                type == that.type &&
                Objects.equals(date, that.date) &&
                Objects.equals(price, that.price) &&
                Objects.equals(quantity, that.quantity) &&
                Objects.equals(amount, that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, code, type, date, price, quantity, amount, lineNumber);
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "name='" + name + '\'' +
                ", code='" + code + '\'' +
                ", type=" + type +
                ", date=" + date +
                ", price=" + price +
                ", quantity=" + quantity +
                ", amount=" + amount +
                ", description='" + description + '\'' +
                ", remarks='" + remarks + '\'' +
                ", lineNumber=" + lineNumber +
                '}';
    }
}