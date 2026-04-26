package com.transactionprocessor;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.Sheet;

/**
 * Utility to list all sheets in the spreadsheet
 */
public class ListSheetsUtility {
    private static final String SPREADSHEET_ID = "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8";
    
    public static void main(String[] args) {
        try {
            ApplicationConfig appConfig = new ApplicationConfig();
            GoogleSheetsConfig config = new GoogleSheetsConfig(appConfig);
            var sheetsService = config.getSheetsService();
            
            Spreadsheet spreadsheet = sheetsService.spreadsheets().get(SPREADSHEET_ID).execute();
            
            System.out.println("=== Sheets in Spreadsheet ===");
            for (Sheet sheet : spreadsheet.getSheets()) {
                String title = sheet.getProperties().getTitle();
                Integer sheetId = sheet.getProperties().getSheetId();
                System.out.printf("ID: %d | Name: %s%n", sheetId, title);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
