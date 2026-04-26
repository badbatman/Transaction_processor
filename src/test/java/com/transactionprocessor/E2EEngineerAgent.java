package com.transactionprocessor;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E Engineer Agent - Coordinates application deployment and fixing
 * Implements the Engineer role in the two-agent verification system
 *
 * Responsibilities:
 * 1. Perform rollback to clean state
 * 2. Run the application
 * 3. Collect logs and reports
 * 4. Analyze issues reported by Tester Agent
 * 5. Implement fixes and re-run
 */
public class E2EEngineerAgent {
    private static final Logger logger = LoggerFactory.getLogger(E2EEngineerAgent.class);

    private E2ETestRunner testRunner;
    private String spreadsheetId;
    private String csvPath;
    private String sheetYear;
    private String sheetMonth;
    private int iterationCount = 0;
    private List<FixAttempt> fixAttempts = new ArrayList<>();

    public E2EEngineerAgent(String spreadsheetId, String csvPath, String sheetYear, String sheetMonth)
            throws Exception {
        this.spreadsheetId = spreadsheetId;
        this.csvPath = csvPath;
        this.sheetYear = sheetYear;
        this.sheetMonth = sheetMonth;
        this.testRunner = new E2ETestRunner(
            spreadsheetId,
            "Transaction records(Alpha)",
            "Transaction records(Cash Flow)"
        );
    }

    /**
     * Run one iteration of testing/fixing cycle
     */
    public IterationResult runIteration(List<E2ETesterAgent.TestIssue> previousIssues) throws Exception {
        iterationCount++;
        logger.info("\n╔════════════════════════════════════════════════════════╗");
        logger.info("║        E2E ENGINEER AGENT - Iteration #{}              ║", iterationCount);
        logger.info("╚════════════════════════════════════════════════════════╝\n");

        IterationResult result = new IterationResult(iterationCount);

        try {
            // Step 1: Perform rollback
            logger.info("\n[Iteration {}] Step 1: Performing rollback", iterationCount);
            result.setRollbackStatus("IN_PROGRESS");

            E2ETestRunner.E2ETestResults testResults = testRunner.runCompleteTest(csvPath, sheetYear, sheetMonth);
            result.setRollbackStatus("COMPLETED");
            result.setTestResults(testResults);

            logger.info("[Iteration {}] Step 2: Rollback and application execution completed", iterationCount);

            // Step 3: Analyze issues from previous iteration (if any)
            if (previousIssues != null && !previousIssues.isEmpty()) {
                logger.info("[Iteration {}] Step 3: Analyzing {} issues from previous iteration",
                    iterationCount, previousIssues.size());

                for (E2ETesterAgent.TestIssue issue : previousIssues) {
                    logger.warn("  [Issue] {}", issue.toString());
                    analyzeAndRecordFix(issue);
                }

                result.setFixesAttempted(previousIssues.size());
            } else {
                logger.info("[Iteration {}] Step 3: No issues to fix from previous iteration", iterationCount);
            }

            result.setStatus("COMPLETED");

        } catch (Exception e) {
            logger.error("[Iteration {}] FAILED with exception", iterationCount, e);
            result.setStatus("FAILED");
            result.setError(e);
        }

        return result;
    }

    /**
     * Analyze issue and record fix attempt
     */
    private void analyzeAndRecordFix(E2ETesterAgent.TestIssue issue) {
        String analysisResult = "ANALYZED";

        switch (issue.issueType) {
            case "NULL_POINTER_EXCEPTION":
                logger.info("  → Analyzing NPE: {}", issue.description);
                analysisResult = "NPE_IDENTIFIED - Check for null field initialization";
                break;

            case "DUPLICATE_TRANSACTIONS_FOUND":
                logger.info("  → Analyzing duplicate: {}", issue.description);
                analysisResult = "DUPLICATE_DETECTION_ISSUE - Verify fee normalization logic";
                break;

            case "GRID_LIMIT_EXCEEDED":
                logger.info("  → Analyzing grid limit: {}", issue.description);
                analysisResult = "GRID_LIMIT_ISSUE - Sheet has exceeded max rows";
                break;

            case "API_ERROR":
                logger.info("  → Analyzing API error: {}", issue.description);
                analysisResult = "API_ISSUE - Check connectivity and authentication";
                break;

            case "TRANSACTION_PROCESSING_FAILED":
                logger.info("  → Analyzing processing failure: {}", issue.description);
                analysisResult = "PROCESSING_ISSUE - Check transaction data and logic";
                break;

            case "AUTH_FAILED":
                logger.info("  → Analyzing auth failure: {}", issue.description);
                analysisResult = "AUTH_ISSUE - Credentials may be invalid";
                break;

            case "NO_FORMATTING_APPLIED":
                logger.info("  → Analyzing formatting: {}", issue.description);
                analysisResult = "FORMATTING_ISSUE - Transaction processing may not be running";
                break;

            default:
                logger.info("  → Analyzing unknown issue: {}", issue.issueType);
                analysisResult = "UNKNOWN_ISSUE";
                break;
        }

        fixAttempts.add(new FixAttempt(issue.issueType, analysisResult, iterationCount));
        logger.info("  ✓ Analysis complete: {}", analysisResult);
    }

    /**
     * Get current status
     */
    public EngineerStatus getStatus() {
        return new EngineerStatus(
            iterationCount,
            fixAttempts.stream().filter(f -> f.status.contains("COMPLETED")).count(),
            fixAttempts.size()
        );
    }

    /**
     * Generate engineer report
     */
    public String generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n╔════════════════════════════════════════════════════════╗\n");
        report.append("║        E2E ENGINEER AGENT - WORK REPORT              ║\n");
        report.append("╚════════════════════════════════════════════════════════╝\n\n");

        report.append("Iterations Completed: ").append(iterationCount).append("\n");
        report.append("Fix Attempts: ").append(fixAttempts.size()).append("\n\n");

        if (!fixAttempts.isEmpty()) {
            report.append("FIX ATTEMPTS:\n");
            report.append("─".repeat(56)).append("\n");
            fixAttempts.forEach(attempt ->
                report.append(String.format("[Iter %d] %s: %s\n",
                    attempt.iterationNumber,
                    attempt.issueType,
                    attempt.status))
            );
        }

        return report.toString();
    }

    // Result container for one iteration
    public static class IterationResult {
        private int iterationNumber;
        private String status = "PENDING";
        private String rollbackStatus = "PENDING";
        private int fixesAttempted = 0;
        private E2ETestRunner.E2ETestResults testResults;
        private Exception error;

        public IterationResult(int iterationNumber) {
            this.iterationNumber = iterationNumber;
        }

        public void setStatus(String status) { this.status = status; }
        public void setRollbackStatus(String status) { this.rollbackStatus = status; }
        public void setFixesAttempted(int count) { this.fixesAttempted = count; }
        public void setTestResults(E2ETestRunner.E2ETestResults results) { this.testResults = results; }
        public void setError(Exception e) { this.error = e; }

        public String getStatus() { return status; }
        public int getFixesAttempted() { return fixesAttempted; }
        public E2ETestRunner.E2ETestResults getTestResults() { return testResults; }
    }

    // Fix attempt record
    private static class FixAttempt {
        String issueType;
        String status;
        int iterationNumber;

        FixAttempt(String issueType, String status, int iterationNumber) {
            this.issueType = issueType;
            this.status = status;
            this.iterationNumber = iterationNumber;
        }
    }

    // Engineer status
    public static class EngineerStatus {
        int totalIterations;
        long fixesCompleted;
        int totalFixAttempts;

        EngineerStatus(int iterations, long completed, int total) {
            this.totalIterations = iterations;
            this.fixesCompleted = completed;
            this.totalFixAttempts = total;
        }
    }
}
