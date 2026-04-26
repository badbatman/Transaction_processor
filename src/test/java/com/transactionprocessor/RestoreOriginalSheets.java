package com.transactionprocessor;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.service.RollbackService;

/**
 * Utility to restore sheet names from backups
 */
public class RestoreOriginalSheets {
    private static final String SPREADSHEET_ID = "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8";
    
    public static void main(String[] args) {
        try {
            ApplicationConfig appConfig = new ApplicationConfig();
            GoogleSheetsConfig config = new GoogleSheetsConfig(appConfig);
            var sheetsService = new com.transactionprocessor.service.GoogleSheetsService(config);
            RollbackService rollbackService = new RollbackService(sheetsService);
            
            System.out.println("=== Restoring Original Sheet Names ===");
            
            // Rename the most recent backup back to original name
            // Alpha sheet
            String alphaBackup = "Transaction_records_Alpha__backup_20260314182848";
            String alphaOriginal = "Transaction records(Alpha)";
            System.out.println("Renaming: " + alphaBackup + " -> " + alphaOriginal);
            rollbackService.renameSheet(SPREADSHEET_ID, alphaBackup, alphaOriginal);
            System.out.println("✅ Alpha sheet restored");
            
            // Cash Flow sheet  
            String cashFlowBackup = "Transaction_records_Cash_Flow__backup_20260314182850";
            String cashFlowOriginal = "Transaction records(Cash Flow)";
            System.out.println("Renaming: " + cashFlowBackup + " -> " + cashFlowOriginal);
            rollbackService.renameSheet(SPREADSHEET_ID, cashFlowBackup, cashFlowOriginal);
            System.out.println("✅ Cash Flow sheet restored");
            
            System.out.println("\n=== Restoration Complete ===");
            System.out.println("Original sheet names have been restored.");
            
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
