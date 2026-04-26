package com.transactionprocessor;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.model.GoogleSheetsRow;

import java.util.List;

/**
 * Verify dividend transactions have correct formatting and description
 */
public class DividendVerifier {
    private static final String SPREADSHEET_ID = "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8";
    
    public static void main(String[] args) {
        try {
            ApplicationConfig appConfig = new ApplicationConfig();
            GoogleSheetsConfig config = new GoogleSheetsConfig(appConfig);
            GoogleSheetsService service = new GoogleSheetsService(config);
            
            List<GoogleSheetsRow> rows = service.readSheet(SPREADSHEET_ID, "Transaction records(Cash Flow)");
            
            System.out.println("=== Dividend Transaction Verification ===\n");
            
            int dividendCount = 0;
            int withDescription = 0;
            int withFormatting = 0;
            
            for (int i = 0; i < rows.size(); i++) {
                GoogleSheetsRow row = rows.get(i);
                
                // Check if this is a dividend row (strikethrough + bold + italic)
                if (row.isStrikethrough() && row.isBold() && row.isItalic()) {
                    // Check if it has close price (dividend per share) but no open price
                    if (row.getClosePrice() != null && 
                        (row.getOpenPrice() == null || row.getOpenPrice().compareTo(java.math.BigDecimal.ZERO) == 0)) {
                        
                        dividendCount++;
                        int rowNum = i + 1;
                        
                        boolean hasDescription = row.getDescription() != null && !row.getDescription().isEmpty();
                        if (hasDescription) withDescription++;
                        
                        System.out.printf("Row %d: Label=%s, Description='%s', ClosePrice=%s%n",
                            rowNum,
                            row.getLabel(),
                            row.getDescription() != null ? row.getDescription() : "NULL",
                            row.getClosePrice());
                        
                        System.out.printf("  Formatting: Strike=%b, Bold=%b, Italic=%b%n",
                            row.isStrikethrough(), row.isBold(), row.isItalic());
                        
                        if (!hasDescription) {
                            System.out.println("  ❌ MISSING: Description '派息'");
                        } else if (!"派息".equals(row.getDescription())) {
                            System.out.println("  ❌ WRONG: Description should be '派息' but got '" + row.getDescription() + "'");
                        } else {
                            System.out.println("  ✅ OK: Description is '派息'");
                        }
                    }
                }
            }
            
            System.out.println("\n=== Summary ===");
            System.out.println("Total dividend rows found: " + dividendCount);
            System.out.println("With description: " + withDescription);
            System.out.println("With formatting: " + dividendCount + " (all should have strikethrough+bold+italic)");
            
            if (dividendCount > 0 && withDescription == dividendCount) {
                System.out.println("\n✅ PASS: All dividend rows have '派息' description");
            } else if (dividendCount > 0) {
                System.out.println("\n❌ FAIL: Some dividend rows missing '派息' description");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
