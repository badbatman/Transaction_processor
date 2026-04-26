package com.transactionprocessor;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.model.GoogleSheetsRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.io.IOException;
import java.util.List;

/**
 * Utility to read and display Google Sheets data for verification.
 * 
 * Usage:
 *   mvn exec:java -Dexec.mainClass="com.transactionprocessor.SheetsReader" \
 *     -Dsheet.name="Transaction records(Alpha)"
 */
public class SheetsReader {
    private static final Logger logger = LoggerFactory.getLogger(SheetsReader.class);
    
    private static final String SPREADSHEET_ID = System.getProperty("spreadsheet.id", 
        "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8");
    
    public static void main(String[] args) {
        String sheetName = System.getProperty("sheet.name", "Transaction records(Alpha)");
        
        logger.info("=== Reading Google Sheets Data ===");
        logger.info("Spreadsheet ID: {}", SPREADSHEET_ID);
        logger.info("Sheet Name: {}", sheetName);
        
        try {
            ApplicationConfig appConfig = new ApplicationConfig();
            GoogleSheetsConfig config = new GoogleSheetsConfig(appConfig);
            GoogleSheetsService googleSheetsService = new GoogleSheetsService(config);
            
            List<GoogleSheetsRow> rows = googleSheetsService.readSheet(SPREADSHEET_ID, sheetName);
            
            logger.info("\n=== Total Rows: {} ===", rows.size());
            
            // Print header
            System.out.println("\n=== " + sheetName + " ===");
            if (!rows.isEmpty()) {
                GoogleSheetsRow header = rows.get(0);
                printRow(header, "HDR");
                
                // Print data rows
                for (int i = 1; i < rows.size(); i++) {
                    GoogleSheetsRow row = rows.get(i);
                    printRow(row, String.format("%3d", i));
                }
            } else {
                System.out.println("No data found in sheet.");
            }
            
        } catch (IOException | GeneralSecurityException e) {
            logger.error("Failed to read sheet", e);
            System.exit(1);
        }
    }
    
    private static void printRow(GoogleSheetsRow row, String rowNum) {
        System.out.printf("%s | A:%-20s | B:%-12s | C:%-10s | D:%-10s | E:%-8s | F:%-10s | G:%-10s | H:%-10s | I:%-10s | Valid:%-5s | Strike:%-5s%n",
            rowNum,
            safeString(row.getLabel()),
            safeString(row.getDomain()),
            safeString(row.getOpenTime()),
            safeString(row.getOpenPrice()),
            safeString(row.getNumberOfStock()),
            safeString(row.getOpenFeeTax()),
            safeString(row.getCloseTime()),
            safeString(row.getClosePrice()),
            safeString(row.getCloseFeeTax()),
            row.isValid(),
            row.isStrikethrough()
        );
    }
    
    private static String safeString(Object obj) {
        return obj != null ? obj.toString() : "null";
    }
}
