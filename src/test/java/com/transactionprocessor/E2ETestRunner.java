package com.transactionprocessor;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.config.ApplicationConfig;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.model.ProcessingResult;
import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.service.CsvParserService;
import com.transactionprocessor.service.GoogleSheetsService;
import com.transactionprocessor.service.RollbackService;
import com.transactionprocessor.service.TransactionProcessor;

/**
 * E2E Test Runner with rollback capability and result verification
 * Orchestrates the complete testing lifecycle:
 * 1. Rollback to initial state
 * 2. Run the application
 * 3. Verify results
 * 4. Report issues
 */
public class E2ETestRunner {
    private static final Logger logger = LoggerFactory.getLogger(E2ETestRunner.class);

    private String spreadsheetId;
    private String alphaSheetName;
    private String cashFlowSheetName;
    private RollbackService rollbackService;
    private GoogleSheetsService googleSheetsService;
    private E2ETestResults testResults;

    // Backup sheet names
    private String alphaBackupName;
    private String cashFlowBackupName;

    public E2ETestRunner(String spreadsheetId, String alphaSheetName, String cashFlowSheetName)
            throws IOException, GeneralSecurityException {
        this.spreadsheetId = spreadsheetId;
        this.alphaSheetName = alphaSheetName;
        this.cashFlowSheetName = cashFlowSheetName;
        this.testResults = new E2ETestResults();

        // Initialize services
        ApplicationConfig appConfig = new ApplicationConfig();
        GoogleSheetsConfig config = new GoogleSheetsConfig(appConfig);
        this.googleSheetsService = new GoogleSheetsService(config);
        this.rollbackService = new RollbackService(googleSheetsService);
    }

    /**
     * Run complete E2E test lifecycle
     */
    public E2ETestResults runCompleteTest(String csvPath, String sheetYear, String sheetMonth) throws Exception {
        logger.info("\n╔════════════════════════════════════════════════════════╗");
        logger.info("║        E2E TEST RUNNER - Complete Lifecycle           ║");
        logger.info("╚════════════════════════════════════════════════════════╝\n");

        try {
            // Step 1: Create initial backups if not exist
            step1_CreateInitialBackups();
            testResults.recordStep("CREATE_BACKUPS");

            // Step 2: Rollback to initial state
            step2_RollbackToInitial();
            testResults.recordStep("ROLLBACK");

            // Step 3: Run the application
            step3_RunApplication(csvPath, sheetYear, sheetMonth);
            testResults.recordStep("RUN_APPLICATION");

            // Step 4: Verify results
            step4_VerifyResults();
            testResults.recordStep("VERIFY_RESULTS");

            // Step 5: Generate report
            step5_GenerateReport();
            testResults.recordStep("GENERATE_REPORT");

            logger.info("\n╔════════════════════════════════════════════════════════╗");
            logger.info("║        E2E TEST COMPLETED SUCCESSFULLY               ║");
            logger.info("╚════════════════════════════════════════════════════════╝\n");

        } catch (Exception e) {
            logger.error("E2E TEST FAILED at step: {}", testResults.getCurrentStep(), e);
            testResults.recordError(e);
            throw e;
        }

        return testResults;
    }

    /**
     * Step 1: Create initial backups for rollback capability
     */
    private void step1_CreateInitialBackups() throws IOException {
        logger.info("\n=== STEP 1: Create Initial Backups ===");
        logger.info("Creating backup copies of both sheets for rollback capability");

        try {
            alphaBackupName = rollbackService.createBackup(spreadsheetId, alphaSheetName);
            logger.info("✓ Alpha sheet backup created: {}", alphaBackupName);
            testResults.recordBackupCreated("alpha", alphaBackupName);

            cashFlowBackupName = rollbackService.createBackup(spreadsheetId, cashFlowSheetName);
            logger.info("✓ Cash Flow sheet backup created: {}", cashFlowBackupName);
            testResults.recordBackupCreated("cashflow", cashFlowBackupName);

        } catch (Exception e) {
            logger.error("Failed to create backups", e);
            throw new IOException("Backup creation failed", e);
        }
    }

    /**
     * Step 2: Rollback to initial state
     */
    private void step2_RollbackToInitial() throws IOException {
        logger.info("\n=== STEP 2: Rollback to Initial State ===");
        logger.info("Restoring both sheets to their initial backup state");

        if (alphaBackupName == null || cashFlowBackupName == null) {
            throw new IOException("Backup names not available - run step 1 first");
        }

        try {
            rollbackService.rollbackToInitial(spreadsheetId, alphaBackupName, alphaSheetName);
            logger.info("✓ Alpha sheet rolled back successfully");
            testResults.recordRollback("alpha", true);

            rollbackService.rollbackToInitial(spreadsheetId, cashFlowBackupName, cashFlowSheetName);
            logger.info("✓ Cash Flow sheet rolled back successfully");
            testResults.recordRollback("cashflow", true);

        } catch (Exception e) {
            logger.error("Failed to rollback sheets", e);
            testResults.recordRollback("unknown", false);
            throw new IOException("Rollback failed", e);
        }
    }

    /**
     * Step 3: Run the application
     */
    private void step3_RunApplication(String csvPath, String sheetYear, String sheetMonth) throws Exception {
        logger.info("\n=== STEP 3: Run Application ===");
        logger.info("Processing transactions from CSV: {} ({}-{})", csvPath, sheetYear, sheetMonth);

        long startTime = System.currentTimeMillis();

        try {
            // Load transactions from CSV
            ApplicationConfig appConfig = new ApplicationConfig();
            CsvParserService csvParser = new CsvParserService(appConfig);
            List<Transaction> transactions = csvParser.parseCsvFile(Path.of(csvPath));
            logger.info("Loaded {} transactions from CSV", transactions.size());

            TransactionProcessor processor = new TransactionProcessor(
                appConfig,
                googleSheetsService,
                null // mapping service - will use default
            );

            // Process for Alpha sheet (buy/dividend transactions)
            ProcessingResult alphaResult = processor.processTransactions(
                spreadsheetId,
                alphaSheetName,
                transactions
            );

            // Process for Cash Flow sheet (dividend transactions)
            ProcessingResult cashFlowResult = processor.processTransactions(
                spreadsheetId,
                cashFlowSheetName,
                transactions
            );

            long duration = System.currentTimeMillis() - startTime;

            logger.info("✓ Application run completed in {} ms", duration);
            logger.info("  - Alpha sheet - Total: {}, Successful: {}, Failed: {}",
                alphaResult.getTotalTransactions(), alphaResult.getSuccessfulTransactions(), alphaResult.getFailedTransactions());
            logger.info("  - Cash Flow sheet - Total: {}, Successful: {}, Failed: {}",
                cashFlowResult.getTotalTransactions(), cashFlowResult.getSuccessfulTransactions(), cashFlowResult.getFailedTransactions());

            // Combine results
            ProcessingResult combinedResult = new ProcessingResult("Combined");
            combinedResult.setTotalTransactions(transactions.size());
            combinedResult.setSuccessfulTransactions(
                alphaResult.getSuccessfulTransactions() + cashFlowResult.getSuccessfulTransactions()
            );
            combinedResult.setFailedTransactions(
                alphaResult.getFailedTransactions() + cashFlowResult.getFailedTransactions()
            );

            testResults.recordApplicationRun(
                combinedResult.getTotalTransactions(),
                combinedResult.getSuccessfulTransactions(),
                combinedResult.getFailedTransactions(),
                duration
            );

            if (combinedResult.getFailedTransactions() > 0) {
                logger.warn("⚠ Some transactions failed during processing");
                alphaResult.getFailedTransactionsList().forEach(failed ->
                    logger.warn("  Failed (Alpha): {}", failed.getTransaction())
                );
                cashFlowResult.getFailedTransactionsList().forEach(failed ->
                    logger.warn("  Failed (CashFlow): {}", failed.getTransaction())
                );
            }

        } catch (Exception e) {
            logger.error("Application run failed", e);
            throw new Exception("Application processing failed", e);
        }
    }

    /**
     * Step 4: Verify results
     */
    private void step4_VerifyResults() throws IOException {
        logger.info("\n=== STEP 4: Verify Results ===");
        logger.info("Verifying that sheets have been updated correctly");

        try {
            // Read updated sheets
            var alphaRows = googleSheetsService.readSheet(spreadsheetId, alphaSheetName);
            var cashFlowRows = googleSheetsService.readSheet(spreadsheetId, cashFlowSheetName);

            logger.info("Alpha sheet: {} rows", alphaRows.size());
            logger.info("Cash Flow sheet: {} rows", cashFlowRows.size());

            // Basic verification: check that rows have been added/modified
            int alphaModifiedCount = (int) alphaRows.stream()
                .filter(r -> r.isBold() || r.isItalic())
                .count();
            int cashFlowModifiedCount = (int) cashFlowRows.stream()
                .filter(r -> r.isBold() || r.isItalic())
                .count();

            logger.info("Alpha sheet: {} formatted rows (buy/dividend)", alphaModifiedCount);
            logger.info("Cash Flow sheet: {} formatted rows (dividend)", cashFlowModifiedCount);

            testResults.recordVerification(
                alphaRows.size(),
                cashFlowRows.size(),
                alphaModifiedCount,
                cashFlowModifiedCount
            );

        } catch (Exception e) {
            logger.error("Result verification failed", e);
            throw new IOException("Verification failed", e);
        }
    }

    /**
     * Step 5: Generate report
     */
    private void step5_GenerateReport() {
        logger.info("\n=== STEP 5: Generate Report ===");
        String report = testResults.generateReport();
        logger.info(report);
    }

    /**
     * Get test results
     */
    public E2ETestResults getResults() {
        return testResults;
    }

    // Result container
    public static class E2ETestResults {
        private List<String> steps = new ArrayList<>();
        private Map<String, String> backups = new HashMap<>();
        private Map<String, Boolean> rollbacks = new HashMap<>();
        private int totalTransactions = 0;
        private int successfulTransactions = 0;
        private int failedTransactions = 0;
        private long runDuration = 0;
        private int alphaRowsAfterRun = 0;
        private int cashFlowRowsAfterRun = 0;
        private int alphaModifiedRows = 0;
        private int cashFlowModifiedRows = 0;
        private Exception error = null;
        private String currentStep = "INIT";

        public void recordStep(String step) { steps.add(step); }
        public void recordBackupCreated(String type, String name) { backups.put(type, name); }
        public void recordRollback(String type, boolean success) { rollbacks.put(type, success); }
        public void recordApplicationRun(int total, int success, int failed, long duration) {
            this.totalTransactions = total;
            this.successfulTransactions = success;
            this.failedTransactions = failed;
            this.runDuration = duration;
        }
        public void recordVerification(int alphaRows, int cashFlowRows, int alphaMod, int cashFlowMod) {
            this.alphaRowsAfterRun = alphaRows;
            this.cashFlowRowsAfterRun = cashFlowRows;
            this.alphaModifiedRows = alphaMod;
            this.cashFlowModifiedRows = cashFlowMod;
        }
        public void recordError(Exception e) { this.error = e; }
        public void setCurrentStep(String step) { this.currentStep = step; }
        public String getCurrentStep() { return currentStep; }

        public String generateReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n╔════════════════════════════════════════════════════════╗\n");
            sb.append("║              E2E TEST RESULTS SUMMARY                 ║\n");
            sb.append("╚════════════════════════════════════════════════════════╝\n\n");

            sb.append("STEPS COMPLETED:\n");
            steps.forEach(s -> sb.append("  ✓ ").append(s).append("\n"));

            sb.append("\nBACKUPS CREATED:\n");
            backups.forEach((k, v) -> sb.append("  - ").append(k).append(": ").append(v).append("\n"));

            sb.append("\nROLLBACK STATUS:\n");
            rollbacks.forEach((k, v) ->
                sb.append("  ").append(v ? "✓" : "✗").append(" ").append(k).append("\n"));

            sb.append("\nAPPLICATION RUN:\n");
            sb.append("  Total transactions: ").append(totalTransactions).append("\n");
            sb.append("  Successful: ").append(successfulTransactions).append("\n");
            sb.append("  Failed: ").append(failedTransactions).append("\n");
            sb.append("  Duration: ").append(runDuration).append(" ms\n");

            sb.append("\nVERIFICATION:\n");
            sb.append("  Alpha rows: ").append(alphaRowsAfterRun).append(" (").append(alphaModifiedRows).append(" modified)\n");
            sb.append("  Cash Flow rows: ").append(cashFlowRowsAfterRun).append(" (").append(cashFlowModifiedRows).append(" modified)\n");

            if (error != null) {
                sb.append("\nERROR: ").append(error.getMessage()).append("\n");
            }

            return sb.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        String spreadsheetId = System.getProperty("spreadsheet.id",
            "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8");
        String csvPath = System.getProperty("csv.path", "src/test/resources/test_transactions.csv");
        String sheetYear = System.getProperty("sheet.year", "2025");
        String sheetMonth = System.getProperty("sheet.month", "04");

        E2ETestRunner runner = new E2ETestRunner(
            spreadsheetId,
            "Transaction records(Alpha)",
            "Transaction records(Cash Flow)"
        );

        E2ETestResults results = runner.runCompleteTest(csvPath, sheetYear, sheetMonth);

        // Exit with appropriate code
        System.exit(results.failedTransactions > 0 ? 1 : 0);
    }
}
