package com.transactionprocessor;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.service.RollbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.io.IOException;

/**
 * Utility to rollback Google Sheets to initial backup state using RENAME-BASED approach.
 * This should be used between integration test iterations.
 * 
 * Usage:
 *   mvn exec:java -Dexec.mainClass="com.transactionprocessor.RollbackUtility" \
 *     -Drollback.sheet.alpha.name=Transaction_records_Alpha__INITIAL_BACKUP_20260314080823 \
 *     -Drollback.sheet.flow.name=Transaction_records_Cash_Flow__INITIAL_BACKUP_20260314080825
 */
public class RollbackUtility {
    private static final Logger logger = LoggerFactory.getLogger(RollbackUtility.class);
    
    // Configuration from system properties
    private static final String SPREADSHEET_ID = System.getProperty("spreadsheet.id", 
        "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8");
    private static final String ALPHA_SHEET_NAME = "Transaction_records(Alpha)_202504";
    private static final String CASH_FLOW_SHEET_NAME = "Transaction records(Cash Flow)_202504";
    
    public static void main(String[] args) {
        logger.info("=== Rollback Utility (Rename-Based) ===");
        logger.info("Rolling back Google Sheets to initial backup state");
        logger.info("Spreadsheet ID: {}", SPREADSHEET_ID);
        
        try {
            // Initialize configuration
            ApplicationConfig appConfig = new ApplicationConfig();
            GoogleSheetsConfig config = new GoogleSheetsConfig(appConfig);
            GoogleSheetsService googleSheetsService = new GoogleSheetsService(config);
            RollbackService rollbackService = new RollbackService(googleSheetsService);
            
            // Get backup names from system properties or use defaults
            String alphaBackupName = System.getProperty("rollback.sheet.alpha.name", 
                getLatestBackupName(ALPHA_SHEET_NAME));
            String cashFlowBackupName = System.getProperty("rollback.sheet.flow.name", 
                getLatestBackupName(CASH_FLOW_SHEET_NAME));
            
            if (alphaBackupName == null || cashFlowBackupName == null) {
                logger.error("Backup names not found. Please specify via system properties.");
                System.exit(1);
            }
            
            logger.info("\n=== Rollback Configuration ===");
            logger.info("Alpha Sheet: {} <- {}", ALPHA_SHEET_NAME, alphaBackupName);
            logger.info("Cash Flow Sheet: {} <- {}", CASH_FLOW_SHEET_NAME, cashFlowBackupName);
            
            // Rollback Alpha sheet
            logger.info("\n=== Rolling Back Alpha Sheet ===");
            rollbackService.rollbackToInitial(SPREADSHEET_ID, alphaBackupName, ALPHA_SHEET_NAME);
            logger.info("✓ Alpha sheet rolled back successfully");
            
            // Rollback Cash Flow sheet
            logger.info("\n=== Rolling Back Cash Flow Sheet ===");
            rollbackService.rollbackToInitial(SPREADSHEET_ID, cashFlowBackupName, CASH_FLOW_SHEET_NAME);
            logger.info("✓ Cash Flow sheet rolled back successfully");
            
            logger.info("\n=== Rollback Complete ===");
            logger.info("Both sheets have been restored to their initial backup state.");
            logger.info("You can now re-run the transaction processor for clean testing.");
            
        } catch (IOException | GeneralSecurityException e) {
            logger.error("Failed to rollback sheets", e);
            System.exit(1);
        }
    }
    
    /**
     * Helper method to find the latest backup sheet name
     * This is a placeholder - in practice you would query Google Sheets API
     * to list all sheets and find the most recent backup
     */
    private static String getLatestBackupName(String originalSheetName) {
        // For now, return null to force user to specify backup name
        // Future enhancement: implement sheet listing logic
        logger.warn("Could not auto-detect backup name for: {}", originalSheetName);
        logger.warn("Please specify via -Drollback.sheet.{}.name=<backup_name>", 
            originalSheetName.contains("Alpha") ? "alpha" : "flow");
        return null;
    }
}
