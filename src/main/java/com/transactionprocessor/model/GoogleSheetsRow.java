package com.transactionprocessor.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a row in Google Sheets with all columns A-T
 * Column mapping:
 * A: Label (标签)
 * B: Domain (领域) 
 * C: Open time (开仓时间)
 * D: Open price (开仓价格)
 * E: Number of stock (股票数量)
 * F: Open Fee + Tax (开仓费用+税)
 * G: Close time (平仓时间)
 * H: Close price (平仓价格)
 * I: Close Fee + Tax (平仓费用+税)
 * J-Q: Formula columns (需要保留)
 * R: Description (说明)
 * S: Region (地区)
 * T: Reserved (预留列)
 */
public class GoogleSheetsRow {
    private String label;           // Column A
    private String domain;          // Column B
    private LocalDate openTime;     // Column C
    private BigDecimal openPrice;   // Column D
    private BigDecimal numberOfStock; // Column E
    private BigDecimal openFeeTax;  // Column F
    private LocalDate closeTime;    // Column G
    private BigDecimal closePrice;  // Column H
    private BigDecimal closeFeeTax; // Column I
    private List<String> formulaColumns; // Columns J-Q (8 columns)
    private String description;     // Column R
    private String region;          // Column S
    private String reserved;        // Column T
    
    // Formatting attributes
    private boolean strikethrough;
    private boolean bold;
    private boolean italic;
    
    // Row metadata
    private Integer rowNumber;  // Use wrapper type to allow null for new rows
    private boolean isValid; // Whether this row is valid (not strikethrough in sheets)
    private boolean isDirty; // Whether this row has been modified and needs to be written back

    public GoogleSheetsRow() {
        this.formulaColumns = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            formulaColumns.add("");
        }
        this.isValid = true;
        this.isDirty = false; // New rows are not dirty until modified
    }

    public GoogleSheetsRow(String label, String domain, LocalDate openTime, BigDecimal openPrice,
                          BigDecimal numberOfStock, BigDecimal openFeeTax, String description, String region) {
        this();
        this.label = label;
        this.domain = domain;
        this.openTime = openTime;
        this.openPrice = openPrice;
        this.numberOfStock = numberOfStock;
        this.openFeeTax = openFeeTax;
        this.description = description;
        this.region = region;
    }

    // Getters and setters
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public LocalDate getOpenTime() { return openTime; }
    public void setOpenTime(LocalDate openTime) { this.openTime = openTime; }

    public BigDecimal getOpenPrice() { return openPrice; }
    public void setOpenPrice(BigDecimal openPrice) { this.openPrice = openPrice; }

    public BigDecimal getNumberOfStock() { return numberOfStock; }
    public void setNumberOfStock(BigDecimal numberOfStock) { this.numberOfStock = numberOfStock; }

    public BigDecimal getOpenFeeTax() { return openFeeTax; }
    public void setOpenFeeTax(BigDecimal openFeeTax) { this.openFeeTax = openFeeTax; }

    public LocalDate getCloseTime() { return closeTime; }
    public void setCloseTime(LocalDate closeTime) { this.closeTime = closeTime; }

    public BigDecimal getClosePrice() { return closePrice; }
    public void setClosePrice(BigDecimal closePrice) { this.closePrice = closePrice; }

    public BigDecimal getCloseFeeTax() { return closeFeeTax; }
    public void setCloseFeeTax(BigDecimal closeFeeTax) { this.closeFeeTax = closeFeeTax; }

    public List<String> getFormulaColumns() { return formulaColumns; }
    public void setFormulaColumns(List<String> formulaColumns) { this.formulaColumns = formulaColumns; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getReserved() { return reserved; }
    public void setReserved(String reserved) { this.reserved = reserved; }

    public boolean isStrikethrough() { return strikethrough; }
    public void setStrikethrough(boolean strikethrough) { this.strikethrough = strikethrough; }

    public boolean isBold() { return bold; }
    public void setBold(boolean bold) { this.bold = bold; }

    public boolean isItalic() { return italic; }
    public void setItalic(boolean italic) { this.italic = italic; }

    public Integer getRowNumber() { return rowNumber; }
    public void setRowNumber(Integer rowNumber) { this.rowNumber = rowNumber; }

    public boolean isValid() { return isValid; }
    public void setValid(boolean valid) { isValid = valid; }

    public boolean isDirty() { return isDirty; }
    public void setDirty(boolean dirty) { isDirty = dirty; }

    /**
     * Convert this row to a list of values for Google Sheets API
     * Preserves numeric types for proper Google Sheets integration
     */
    public List<Object> toSheetValues() {
        List<Object> values = new ArrayList<>();
        values.add(label != null ? label : "");
        values.add(domain != null ? domain : "");
        values.add(openTime != null ? openTime.toString() : "");
        // Keep numeric values as objects, not strings
        values.add(openPrice != null ? openPrice : BigDecimal.ZERO);
        values.add(numberOfStock != null ? numberOfStock : BigDecimal.ZERO);
        values.add(openFeeTax != null ? openFeeTax : BigDecimal.ZERO);
        values.add(closeTime != null ? closeTime.toString() : "");
        values.add(closePrice != null ? closePrice : BigDecimal.ZERO);
        values.add(closeFeeTax != null ? closeFeeTax : BigDecimal.ZERO);
        
        // Add formula columns (preserve empty strings for formulas)
        for (String formula : formulaColumns) {
            values.add(formula != null ? formula : "");
        }
        
        values.add(description != null ? description : "");
        values.add(region != null ? region : "");
        values.add(reserved != null ? reserved : "");
        
        return values;
    }

    /**
     * Create a copy of this row with the same formula columns
     */
    public GoogleSheetsRow copy() {
        GoogleSheetsRow copy = new GoogleSheetsRow();
        copy.label = this.label;
        copy.domain = this.domain;
        copy.openTime = this.openTime;
        copy.openPrice = this.openPrice;
        copy.numberOfStock = this.numberOfStock;
        copy.openFeeTax = this.openFeeTax;
        copy.closeTime = this.closeTime;
        copy.closePrice = this.closePrice;
        copy.closeFeeTax = this.closeFeeTax;
        copy.formulaColumns = new ArrayList<>(this.formulaColumns);
        copy.description = this.description;
        copy.region = this.region;
        copy.reserved = this.reserved;
        copy.strikethrough = this.strikethrough;
        copy.bold = this.bold;
        copy.italic = this.italic;
        copy.rowNumber = this.rowNumber;
        copy.isValid = this.isValid;
        return copy;
    }

    /**
     * Apply formatting based on transaction type and status
     */
    public void applyFormatting(TransactionType transactionType, boolean isClosed) {
        switch (transactionType) {
            case BUY:
                this.bold = true;
                this.italic = true;
                break;
            case SELL:
            case DIVIDEND:
                this.strikethrough = true;
                this.bold = true;
                this.italic = true;
                break;
        }
        
        if (isClosed) {
            this.strikethrough = true;
        }
    }

    /**
     * Clear all data fields except row number and formatting flags
     */
    public void clearData() {
        this.label = null;
        this.domain = null;
        this.openTime = null;
        this.openPrice = null;
        this.numberOfStock = null;
        this.openFeeTax = null;
        this.closeTime = null;
        this.closePrice = null;
        this.closeFeeTax = null;
        
        // Clear formula columns
        if (this.formulaColumns != null) {
            for (int i = 0; i < this.formulaColumns.size(); i++) {
                this.formulaColumns.set(i, "");
            }
        }
        
        this.description = null;
        this.region = null;
        this.reserved = null;
        this.isValid = true;
    }
    
    @Override
    public String toString() {
        return "GoogleSheetsRow{" +
                "label='" + label + '\'' +
                ", domain='" + domain + '\'' +
                ", openTime=" + openTime +
                ", openPrice=" + openPrice +
                ", numberOfStock=" + numberOfStock +
                ", closeTime=" + closeTime +
                ", closePrice=" + closePrice +
                ", rowNumber=" + rowNumber +
                ", isValid=" + isValid +
                '}';
    }
}