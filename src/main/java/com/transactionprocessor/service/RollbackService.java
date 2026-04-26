package com.transactionprocessor.service;

import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.DuplicateSheetRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.DeleteSheetRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * Service to handle rollback functionality for Google Sheets
 * Creates backups before processing and can restore from backups if needed
 */
public class RollbackService {
    private static final Logger logger = LoggerFactory.getLogger(RollbackService.class);
    
    private final GoogleSheetsService googleSheetsService;
    
    public RollbackService(GoogleSheetsService googleSheetsService) {
        this.googleSheetsService = googleSheetsService;
    }
    
    /**
     * Create a backup of the specified sheet by duplicating it
     * @param spreadsheetId The Google Sheets spreadsheet ID
     * @param sheetName The name of the sheet to backup
     * @return The name of the backup sheet
     * @throws IOException if there's an error communicating with Google Sheets API
     */
    public String createBackup(String spreadsheetId, String sheetName) throws IOException {
        logger.info("Creating backup for sheet: {} in spreadsheet: {}", sheetName, spreadsheetId);
        
        // Generate timestamp for backup name
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String safeSheetName = sheetName.replaceAll("[^A-Za-z0-9_-]", "_");
        String backupName = safeSheetName + "_backup_" + timestamp;
        
        // Duplicate the sheet using Google Sheets API
        int newSheetId = googleSheetsService.duplicateSheet(spreadsheetId, sheetName, backupName, null);
        
        if (newSheetId <= 0) {
            throw new IOException("Failed to create backup sheet, duplicateSheet returned invalid ID: " + newSheetId);
        }
        
        logger.info("Successfully created backup sheet: {} with ID: {}", backupName, newSheetId);
        return backupName;
    }
    
    /**
     * Restore a sheet from its backup using RENAME-BASED approach
     * This is the preferred method for integration testing rollback
     * @param spreadsheetId The Google Sheets spreadsheet ID
     * @param backupSheetName The name of the backup sheet (initial backup)
     * @param originalSheetName The name of the original sheet to restore to
     * @throws IOException if there's an error communicating with Google Sheets API
     */
    public void rollbackToInitial(String spreadsheetId, String backupSheetName, String originalSheetName) throws IOException {
        logger.info("Starting rollback: restoring {} from backup {} in spreadsheet: {}", 
                   originalSheetName, backupSheetName, spreadsheetId);
        
        try {
            // Step 1: Rename current sheet to TO_BE_DELETED_{timestamp}
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String markedForDeletion = originalSheetName + "_TO_BE_DELETED_" + timestamp;
            
            logger.info("Step 1: Renaming current sheet '{}' to '{}'", originalSheetName, markedForDeletion);
            renameSheet(spreadsheetId, originalSheetName, markedForDeletion);
            
            // Step 2: Rename backup sheet to original name
            logger.info("Step 2: Renaming backup sheet '{}' to '{}'", backupSheetName, originalSheetName);
            renameSheet(spreadsheetId, backupSheetName, originalSheetName);
            
            logger.info("Rollback complete: {} restored from {}", originalSheetName, backupSheetName);
            logger.info("Old sheet marked for deletion: {}", markedForDeletion);
            
        } catch (IOException e) {
            logger.error("Failed to rollback: {} -> {}", backupSheetName, originalSheetName, e);
            throw e;
        }
    }
    
    /**
     * Restore a sheet from its backup (legacy method - kept for backward compatibility)
     * @param spreadsheetId The Google Sheets spreadsheet ID
     * @param backupSheetName The name of the backup sheet
     * @param originalSheetName The name of the original sheet to restore to
     * @throws IOException if there's an error communicating with Google Sheets API
     */
    @Deprecated
    public void restoreFromBackup(String spreadsheetId, String backupSheetName, String originalSheetName) throws IOException {
        logger.info("Restoring sheet: {} from backup: {} in spreadsheet: {}", 
                   originalSheetName, backupSheetName, spreadsheetId);
        
        try {
            // First, delete the current sheet (if it exists)
            deleteSheetIfExists(spreadsheetId, originalSheetName);
            
            // Then rename the backup sheet to the original name
            renameSheet(spreadsheetId, backupSheetName, originalSheetName);
            
            logger.info("Successfully restored sheet: {} from backup: {}", originalSheetName, backupSheetName);
            
            // Finally, delete the backup sheet (it's now renamed to original name, so no need to keep backup)
            // Note: After rename, the backup sheet no longer exists under backupSheetName,
            // it now exists under originalSheetName, so we don't need to delete it.
            logger.info("Restore complete. Backup sheet '{}' has been renamed to '{}'", 
                       backupSheetName, originalSheetName);
            
        } catch (IOException e) {
            logger.error("Failed to restore from backup: {}", backupSheetName, e);
            throw e;
        }
    }
    
    /**
     * Delete a sheet if it exists
     */
    private void deleteSheetIfExists(String spreadsheetId, String sheetName) throws IOException {
        try {
            // Get sheet ID by name
            Integer sheetId = googleSheetsService.getSheetIdByName(spreadsheetId, sheetName);
            if (sheetId != null) {
                logger.debug("Deleting existing sheet: {} (ID: {})", sheetName, sheetId);
                
                DeleteSheetRequest deleteRequest = new DeleteSheetRequest();
                deleteRequest.setSheetId(sheetId);
                
                Request request = new Request();
                request.setDeleteSheet(deleteRequest);
                
                BatchUpdateSpreadsheetRequest batchRequest = new BatchUpdateSpreadsheetRequest();
                batchRequest.setRequests(Collections.singletonList(request));
                
                googleSheetsService.getSheetsService().spreadsheets()
                    .batchUpdate(spreadsheetId, batchRequest)
                    .execute();
                    
                logger.debug("Deleted sheet: {}", sheetName);
            }
        } catch (Exception e) {
            logger.warn("Could not delete sheet: {} - it may not exist", sheetName);
        }
    }
    
    /**
     * Rename a sheet - PUBLIC method for external use
     */
    public void renameSheet(String spreadsheetId, String currentName, String newName) throws IOException {
        // Get sheet ID by current name
        Integer sheetId = googleSheetsService.getSheetIdByName(spreadsheetId, currentName);
        if (sheetId == null) {
            throw new IOException("Sheet not found: " + currentName);
        }
        
        logger.info("Renaming sheet ID: {} from '{}' to '{}'", sheetId, currentName, newName);
        
        // Use Google Sheets API to rename the sheet
        com.google.api.services.sheets.v4.model.SheetProperties sheetProperties = 
            new com.google.api.services.sheets.v4.model.SheetProperties();
        sheetProperties.setSheetId(sheetId);
        sheetProperties.setTitle(newName);
        
        com.google.api.services.sheets.v4.model.UpdateSheetPropertiesRequest updateRequest = 
            new com.google.api.services.sheets.v4.model.UpdateSheetPropertiesRequest();
        updateRequest.setProperties(sheetProperties);
        updateRequest.setFields("title");
        
        Request request = new Request();
        request.setUpdateSheetProperties(updateRequest);
        
        BatchUpdateSpreadsheetRequest batchRequest = new BatchUpdateSpreadsheetRequest();
        batchRequest.setRequests(Collections.singletonList(request));
        
        googleSheetsService.getSheetsService().spreadsheets()
            .batchUpdate(spreadsheetId, batchRequest)
            .execute();
            
        logger.info("Renamed sheet from '{}' to '{}'", currentName, newName);
    }
    
    /**
     * Clean up old backup sheets (optional maintenance function)
     * @param spreadsheetId The Google Sheets spreadsheet ID
     * @param maxBackups Maximum number of backup sheets to keep
     */
    public void cleanupOldBackups(String spreadsheetId, int maxBackups) throws IOException {
        logger.info("Cleaning up old backups for spreadsheet: {}, keeping max {} backups", 
                   spreadsheetId, maxBackups);
        
        // This would require listing all sheets and identifying backup sheets
        // Implementation depends on how backup sheets are named/identified
        // For now, this is a placeholder for future enhancement
        logger.debug("Backup cleanup functionality not yet implemented");
    }
}