package com.transactionprocessor;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.service.RollbackService;

/**
 * Utility to create initial backups of Google Sheets before integration testing
 */
public class BackupUtility {
    private static final Logger logger = LoggerFactory.getLogger(BackupUtility.class);
    
    private final GoogleSheetsService googleSheetsService;
    private final RollbackService rollbackService;
    
    public BackupUtility() throws IOException, GeneralSecurityException {
        ApplicationConfig config = new ApplicationConfig();
        GoogleSheetsConfig googleSheetsConfig = new GoogleSheetsConfig(config);
        this.googleSheetsService = new GoogleSheetsService(googleSheetsConfig);
        this.rollbackService = new RollbackService(googleSheetsService);
    }
    
    /**
     * Create backup of specified sheet
     */
    public String createBackup(String spreadsheetId, String sheetName) throws IOException {
        logger.info("Creating backup for sheet: {} in spreadsheet: {}", sheetName, spreadsheetId);
        
        // Generate timestamp for backup name
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String safeSheetName = sheetName.replaceAll("[^A-Za-z0-9_-]", "_");
        String backupName = safeSheetName + "_INITIAL_BACKUP_" + timestamp;
        
        // Duplicate the sheet using Google Sheets API
        int newSheetId = googleSheetsService.duplicateSheet(spreadsheetId, sheetName, backupName, null);
        
        if (newSheetId <= 0) {
            throw new IOException("Failed to create backup sheet, duplicateSheet returned invalid ID: " + newSheetId);
        }
        
        logger.info("Successfully created backup sheet: {} with ID: {}", backupName, newSheetId);
        return backupName;
    }
    
    /**
     * Main method to create backups for both sheets
     */
    public static void main(String[] args) {
        String spreadsheetId = "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8";
        String[] sheetNames = {
            "Transaction records(Alpha)",
            "Transaction records(Cash Flow)"
        };
        
        try {
            BackupUtility backupUtility = new BackupUtility();
            
            logger.info("=== CREATING INITIAL BACKUPS FOR INTEGRATION TEST ===");
            logger.info("Spreadsheet ID: {}", spreadsheetId);
            logger.info("Sheets to backup: {}", (Object[]) sheetNames);
            
            for (String sheetName : sheetNames) {
                try {
                    String backupName = backupUtility.createBackup(spreadsheetId, sheetName);
                    logger.info("✅ Created backup for '{}': {}", sheetName, backupName);
                    System.out.println("BACKUP_CREATED:" + sheetName + ":" + backupName);
                } catch (Exception e) {
                    logger.error("❌ Failed to create backup for sheet: {}", sheetName, e);
                    System.err.println("BACKUP_FAILED:" + sheetName + ":" + e.getMessage());
                }
            }
            
            logger.info("=== BACKUP CREATION COMPLETE ===");
            
        } catch (Exception e) {
            logger.error("Backup utility failed", e);
            System.exit(1);
        }
    }
}
