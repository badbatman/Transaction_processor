package com.transactionprocessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E Tester Agent - Monitors logs and verifies application results
 * Implements the Tester role in the two-agent verification system
 *
 * Responsibilities:
 * 1. Monitor application logs in real-time
 * 2. Detect errors, warnings, and processing issues
 * 3. Verify transaction processing results
 * 4. Report issues to the Engineer Agent
 */
public class E2ETesterAgent {
    private static final Logger logger = LoggerFactory.getLogger(E2ETesterAgent.class);

    private Path logFilePath;
    private Path googleSheetsAccessLogPath;
    private List<TestIssue> detectedIssues = new ArrayList<>();

    public E2ETesterAgent(String logFilePath) {
        this.logFilePath = Paths.get(logFilePath);
        this.googleSheetsAccessLogPath = Paths.get(logFilePath).getParent()
            .resolve("google_sheets_access.log");
    }

    /**
     * Start monitoring the application logs for issues
     */
    public void startMonitoring() {
        logger.info("\n╔════════════════════════════════════════════════════════╗");
        logger.info("║          E2E TESTER AGENT - Monitoring Started         ║");
        logger.info("╚════════════════════════════════════════════════════════╝\n");

        logger.info("Monitoring log file: {}", logFilePath);
        logger.info("Monitoring Google Sheets access log: {}", googleSheetsAccessLogPath);
    }

    /**
     * Check log file for new entries and detect issues
     */
    public List<TestIssue> scanForIssues() throws IOException {
        detectedIssues.clear();

        // Scan main application log
        scanApplicationLog();

        // Scan Google Sheets API interactions
        scanGoogleSheetsLog();

        return detectedIssues;
    }

    /**
     * Scan application log for errors, warnings, and critical issues
     */
    private void scanApplicationLog() throws IOException {
        if (!Files.exists(logFilePath)) {
            logger.warn("Log file not found: {}", logFilePath);
            detectedIssues.add(new TestIssue(
                "LOG_FILE_NOT_FOUND",
                TestIssue.Severity.ERROR,
                "Application log file not accessible: " + logFilePath
            ));
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(logFilePath)) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Check for errors
                if (line.contains("ERROR") || line.contains("[ERROR]")) {
                    String message = extractLogMessage(line);
                    detectedIssues.add(new TestIssue(
                        "ERROR_LOG_ENTRY",
                        TestIssue.Severity.ERROR,
                        message,
                        lineNumber
                    ));
                }
                // Check for duplicate detection
                else if (line.contains("DUPLICATE DETECTED")) {
                    String message = extractLogMessage(line);
                    detectedIssues.add(new TestIssue(
                        "DUPLICATE_DETECTED",
                        TestIssue.Severity.WARNING,
                        message,
                        lineNumber
                    ));
                }
                // Check for API call failures
                else if (line.contains("Api call failed") || line.contains("API error")) {
                    String message = extractLogMessage(line);
                    detectedIssues.add(new TestIssue(
                        "API_ERROR",
                        TestIssue.Severity.ERROR,
                        message,
                        lineNumber
                    ));
                }
                // Check for grid limit exceeded
                else if (line.contains("exceeds grid limits") || line.contains("Max rows")) {
                    String message = extractLogMessage(line);
                    detectedIssues.add(new TestIssue(
                        "GRID_LIMIT_EXCEEDED",
                        TestIssue.Severity.ERROR,
                        message,
                        lineNumber
                    ));
                }
                // Check for authentication issues
                else if (line.contains("401") || line.contains("Unauthorized") ||
                         line.contains("Authentication failed")) {
                    String message = extractLogMessage(line);
                    detectedIssues.add(new TestIssue(
                        "AUTH_FAILED",
                        TestIssue.Severity.ERROR,
                        message,
                        lineNumber
                    ));
                }
                // Check for null pointer exceptions
                else if (line.contains("NullPointerException")) {
                    String message = extractLogMessage(line);
                    detectedIssues.add(new TestIssue(
                        "NULL_POINTER_EXCEPTION",
                        TestIssue.Severity.ERROR,
                        message,
                        lineNumber
                    ));
                }
                // Check for transaction processing failures
                else if (line.contains("Failed to process transaction") ||
                         line.contains("Unable to process")) {
                    String message = extractLogMessage(line);
                    detectedIssues.add(new TestIssue(
                        "TRANSACTION_PROCESSING_FAILED",
                        TestIssue.Severity.ERROR,
                        message,
                        lineNumber
                    ));
                }
                // Check for warnings
                else if (line.contains("WARN") || line.contains("[WARN]")) {
                    String message = extractLogMessage(line);
                    if (!shouldIgnoreWarning(message)) {
                        detectedIssues.add(new TestIssue(
                            "WARNING_LOG_ENTRY",
                            TestIssue.Severity.WARNING,
                            message,
                            lineNumber
                        ));
                    }
                }
            }

            // Note: BufferedReader doesn't support getFilePointer()
            // For simplicity, we're not tracking position for this agent
        }
    }

    /**
     * Scan Google Sheets API log for issues
     */
    private void scanGoogleSheetsLog() throws IOException {
        if (!Files.exists(googleSheetsAccessLogPath)) {
            return; // Optional log, don't report error
        }

        try (BufferedReader reader = Files.newBufferedReader(googleSheetsAccessLogPath)) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Check for API rate limit errors (429)
                if (line.contains("429") || line.contains("Rate limit")) {
                    detectedIssues.add(new TestIssue(
                        "RATE_LIMIT_EXCEEDED",
                        TestIssue.Severity.WARNING,
                        "API rate limit exceeded - retrying",
                        lineNumber
                    ));
                }
                // Check for API call failures
                else if (line.contains("Connection timeout") || line.contains("timeout")) {
                    detectedIssues.add(new TestIssue(
                        "CONNECTION_TIMEOUT",
                        TestIssue.Severity.ERROR,
                        "Connection to Google Sheets API timed out",
                        lineNumber
                    ));
                }
            }
        }
    }

    /**
     * Generate verification report
     */
    public String generateVerificationReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n╔════════════════════════════════════════════════════════╗\n");
        report.append("║        E2E TESTER AGENT - VERIFICATION REPORT         ║\n");
        report.append("╚════════════════════════════════════════════════════════╝\n\n");

        if (detectedIssues.isEmpty()) {
            report.append("✅ NO ISSUES DETECTED\n");
            report.append("All checks passed successfully.\n");
        } else {
            report.append("⚠️  ISSUES DETECTED: ").append(detectedIssues.size()).append("\n\n");

            // Group by severity
            long errors = detectedIssues.stream().filter(i -> i.severity == TestIssue.Severity.ERROR).count();
            long warnings = detectedIssues.stream().filter(i -> i.severity == TestIssue.Severity.WARNING).count();

            report.append("  Errors: ").append(errors).append("\n");
            report.append("  Warnings: ").append(warnings).append("\n\n");

            report.append("DETAILED ISSUES:\n");
            report.append("─".repeat(56)).append("\n");

            // Sort by severity and line number
            detectedIssues.stream()
                .sorted(Comparator.comparing((TestIssue i) -> i.severity)
                                   .reversed()
                                   .thenComparing(i -> i.lineNumber))
                .forEach(issue -> report.append(issue.toString()).append("\n"));
        }

        return report.toString();
    }

    /**
     * Check if transaction counts match expected values
     */
    public void verifyTransactionCounts(int expectedTotal, int actualTotal) {
        if (expectedTotal != actualTotal) {
            detectedIssues.add(new TestIssue(
                "TRANSACTION_COUNT_MISMATCH",
                TestIssue.Severity.ERROR,
                String.format("Expected %d transactions, but processed %d", expectedTotal, actualTotal)
            ));
        }
    }

    /**
     * Check if all rows have required formatting
     */
    public void verifyRowFormatting(String sheetName, int totalRows, int formattedRows, String expectedFormat) {
        if (formattedRows == 0) {
            detectedIssues.add(new TestIssue(
                "NO_FORMATTING_APPLIED",
                TestIssue.Severity.ERROR,
                String.format("Sheet '%s': No rows with %s formatting detected in %d rows",
                    sheetName, expectedFormat, totalRows)
            ));
        }
    }

    /**
     * Check for duplicate transactions in the sheet
     */
    public void verifyNoDuplicateTransactions(String sheetName, int totalRows, int duplicateCount) {
        if (duplicateCount > 0) {
            detectedIssues.add(new TestIssue(
                "DUPLICATE_TRANSACTIONS_FOUND",
                TestIssue.Severity.ERROR,
                String.format("Sheet '%s': Found %d duplicate transactions", sheetName, duplicateCount)
            ));
        }
    }

    // Helper to extract log message
    private String extractLogMessage(String line) {
        // Remove timestamp and log level
        Pattern pattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}.*?(?:\\[.*?\\]\\s+)?(.*)");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return line;
    }

    // Helper to ignore non-critical warnings
    private boolean shouldIgnoreWarning(String message) {
        String[] ignoredPatterns = {
            "Could not auto-detect",
            "Sheet listing logic",
            "deprecated",
            "retry"
        };
        for (String pattern : ignoredPatterns) {
            if (message.toLowerCase().contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Issue detected during testing
     */
    public static class TestIssue {
        public enum Severity { ERROR, WARNING }

        public String issueType;
        public Severity severity;
        public String description;
        public Integer lineNumber;
        public LocalDateTime timestamp;

        public TestIssue(String issueType, Severity severity, String description) {
            this(issueType, severity, description, null);
        }

        public TestIssue(String issueType, Severity severity, String description, Integer lineNumber) {
            this.issueType = issueType;
            this.severity = severity;
            this.description = description;
            this.lineNumber = lineNumber;
            this.timestamp = LocalDateTime.now();
        }

        @Override
        public String toString() {
            String severityStr = severity == Severity.ERROR ? "❌ ERROR" : "⚠️  WARNING";
            String lineStr = lineNumber != null ? String.format(" [Line %d]", lineNumber) : "";
            return String.format("%s [%s]%s: %s", severityStr, issueType, lineStr, description);
        }
    }

    public static void main(String[] args) throws IOException {
        String logPath = System.getProperty("log.path",
            "/Users/bob/ai_projects/Transaction_processor/logs/transaction-processor.log");

        E2ETesterAgent tester = new E2ETesterAgent(logPath);
        tester.startMonitoring();

        // Simulate monitoring
        while (true) {
            try {
                List<E2ETesterAgent.TestIssue> issues = tester.scanForIssues();
                if (!issues.isEmpty()) {
                    System.out.println(tester.generateVerificationReport());
                    break; // Exit after first issues found for demo
                }
                Thread.sleep(1000); // Check every second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
