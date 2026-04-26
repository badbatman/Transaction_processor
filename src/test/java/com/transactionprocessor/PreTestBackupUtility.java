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
 * Utility to create initial backups of both sheets before integration testing.
 * This should be run ONCE before starting integration tests.
 * 
 * Usage:
 *   mvn exec:java -Dexec.mainClass="com.transactionprocessor.PreTestBackupUtility"
 */
public class PreTestBackupUtility {
    private static final Logger logger = LoggerFactory.getLogger(PreTestBackupUtility.class);
    
    // Configuration from environment or properties
    private static final String SPREADSHEET_ID = System.getProperty("spreadsheet.id", 
        "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8");
    private static final String ALPHA_SHEET_NAME = "Transaction records(Alpha)";
    private static final String CASH_FLOW_SHEET_NAME = "Transaction records(Cash Flow)";
    
    public static void main(String[] args) {
        logger.info("=== Pre-Test Backup Utility ===");
        logger.info("Creating initial backups for integration testing");
        logger.info("Spreadsheet ID: {}", SPREADSHEET_ID);
        
        try {
            // Initialize configuration
            ApplicationConfig appConfig = new ApplicationConfig();
            GoogleSheetsConfig config = new GoogleSheetsConfig(appConfig);
            GoogleSheetsService googleSheetsService = new GoogleSheetsService(config);
            RollbackService rollbackService = new RollbackService(googleSheetsService);
            
            // Create backup for Alpha sheet
            logger.info("\n=== Creating backup for Alpha Sheet ===");
            String alphaBackup = rollbackService.createBackup(SPREADSHEET_ID, ALPHA_SHEET_NAME);
            logger.info("✓ Alpha sheet backup created: {}", alphaBackup);
            
            // Create backup for Cash Flow sheet
            logger.info("\n=== Creating backup for Cash Flow Sheet ===");
            String cashFlowBackup = rollbackService.createBackup(SPREADSHEET_ID, CASH_FLOW_SHEET_NAME);
            logger.info("✓ Cash Flow sheet backup created: {}", cashFlowBackup);
            
            logger.info("\n=== Initial Backups Complete ===");
            logger.info("Alpha Backup Name: {}", alphaBackup);
            logger.info("Cash Flow Backup Name: {}", cashFlowBackup);
            logger.info("\nThese backups will be used for rollback during integration testing.");
            logger.info("DO NOT delete these backups until testing is complete.");
            
        } catch (IOException | GeneralSecurityException e) {
            logger.error("Failed to create initial backups", e);
            System.exit(1);
        }
    }
}
