package com.transactionprocessor;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.service.RollbackService;

/**
 * Utility to rollback Google Sheets to initial backup state
 */
public class RollbackToInitialUtility {
    private static final Logger logger = LoggerFactory.getLogger(RollbackToInitialUtility.class);
    
    private final RollbackService rollbackService;
    
    public RollbackToInitialUtility() throws IOException, GeneralSecurityException {
        ApplicationConfig config = new ApplicationConfig();
        GoogleSheetsConfig googleSheetsConfig = new GoogleSheetsConfig(config);
        GoogleSheetsService googleSheetsService = new GoogleSheetsService(googleSheetsConfig);
        this.rollbackService = new RollbackService(googleSheetsService);
    }
    
    /**
     * Restore sheet from backup
     */
    public void restoreFromBackup(String spreadsheetId, String backupSheetName, String originalSheetName) throws IOException {
        logger.info("Restoring sheet: {} from backup: {}", originalSheetName, backupSheetName);
        rollbackService.restoreFromBackup(spreadsheetId, backupSheetName, originalSheetName);
        logger.info("Successfully restored sheet: {} from backup: {}", originalSheetName, backupSheetName);
    }
    
    /**
     * Main method to restore both sheets from initial backups
     */
    public static void main(String[] args) {
        String spreadsheetId = System.getProperty("spreadsheet.id", "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8");
        String backupAlphaName = System.getProperty("backup.alpha.name", "Transaction_records_Alpha__INITIAL_BACKUP_20260314080823");
        String backupCashFlowName = System.getProperty("backup.cashflow.name", "Transaction_records_Cash_Flow__INITIAL_BACKUP_20260314080825");
        
        try {
            RollbackToInitialUtility rollbackUtility = new RollbackToInitialUtility();
            
            logger.info("=== ROLLING BACK TO INITIAL STATE ===");
            logger.info("Spreadsheet ID: {}", spreadsheetId);
            
            // Restore Alpha sheet
            try {
                rollbackUtility.restoreFromBackup(spreadsheetId, backupAlphaName, "Transaction records(Alpha)");
                logger.info("✅ Restored Transaction records(Alpha) from {}", backupAlphaName);
                System.out.println("RESTORED:Transaction records(Alpha)");
            } catch (Exception e) {
                logger.error("❌ Failed to restore Transaction records(Alpha)", e);
                System.err.println("FAILED:Transaction records(Alpha): " + e.getMessage());
            }
            
            // Restore Cash Flow sheet
            try {
                rollbackUtility.restoreFromBackup(spreadsheetId, backupCashFlowName, "Transaction records(Cash Flow)");
                logger.info("✅ Restored Transaction records(Cash Flow) from {}", backupCashFlowName);
                System.out.println("RESTORED:Transaction records(Cash Flow)");
            } catch (Exception e) {
                logger.error("❌ Failed to restore Transaction records(Cash Flow)", e);
                System.err.println("FAILED:Transaction records(Cash Flow): " + e.getMessage());
            }
            
            logger.info("=== ROLLBACK COMPLETE ===");
            
        } catch (Exception e) {
            logger.error("Rollback utility failed", e);
            System.exit(1);
        }
    }
}
