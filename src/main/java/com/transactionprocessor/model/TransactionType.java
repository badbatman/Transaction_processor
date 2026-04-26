package com.transactionprocessor.model;

/**
 * Enum representing different types of transactions
 */
public enum TransactionType {
    BUY("买入", "buy"),
    SELL("卖出", "sell"),
    DIVIDEND("除权除息", "dividend");

    private final String chineseName;
    private final String englishName;

    TransactionType(String chineseName, String englishName) {
        this.chineseName = chineseName;
        this.englishName = englishName;
    }

    public String getChineseName() {
        return chineseName;
    }

    public String getEnglishName() {
        return englishName;
    }

    /**
     * Parse transaction type from string (supports both Chinese and English)
     */
    public static TransactionType fromString(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction type cannot be null or empty");
        }

        String normalizedType = type.trim().toLowerCase();

        for (TransactionType transactionType : values()) {
            if (transactionType.chineseName.equals(type.trim()) ||
                transactionType.englishName.equals(normalizedType)) {
                return transactionType;
            }
        }

        throw new IllegalArgumentException("Unknown transaction type: " + type);
    }

    @Override
    public String toString() {
        return chineseName;
    }
}