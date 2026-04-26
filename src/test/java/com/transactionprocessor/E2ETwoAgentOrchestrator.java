package com.transactionprocessor;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-Agent E2E Verification Coordinator
 * Orchestrates the Engineer and Tester agents in an iterative loop
 *
 * Workflow:
 * 1. Engineer Agent: Rolls back and runs application
 * 2. Tester Agent: Monitors logs and verifies results
 * 3. When issues found: Report to Engineer Agent
 * 4. Engineer Agent: Analyzes and attempts fixes
 * 5. Loop until no issues remain
 */
public class E2ETwoAgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(E2ETwoAgentOrchestrator.class);

    private E2EEngineerAgent engineerAgent;
    private E2ETesterAgent testerAgent;
    private String logFilePath;
    private List<IterationSummary> iterationHistory = new ArrayList<>();
    private int maxIterations = 5; // Safety limit
    private int currentIteration = 0;

    public E2ETwoAgentOrchestrator(
            String spreadsheetId,
            String csvPath,
            String sheetYear,
            String sheetMonth,
            String logFilePath) throws Exception {

        this.logFilePath = logFilePath;
        this.engineerAgent = new E2EEngineerAgent(spreadsheetId, csvPath, sheetYear, sheetMonth);
        this.testerAgent = new E2ETesterAgent(logFilePath);
    }

    /**
     * Execute the complete two-agent E2E testing loop
     */
    public TwoAgentTestResults executeFullVerification() throws Exception {
        logger.info("\n\n");
        logger.info("╔═══════════════════════════════════════════════════════════╗");
        logger.info("║   TWO-AGENT E2E VERIFICATION & FIXING LOOP STARTED       ║");
        logger.info("║   Engineer Agent + Tester Agent Coordination              ║");
        logger.info("╚═══════════════════════════════════════════════════════════╝\n");

        TwoAgentTestResults finalResults = new TwoAgentTestResults();

        List<E2ETesterAgent.TestIssue> currentIssues = new ArrayList<>();

        while (currentIteration < maxIterations) {
            currentIteration++;
            IterationSummary iterationSummary = new IterationSummary(currentIteration);

            logger.info("\n" + "═".repeat(60));
            logger.info("ITERATION #{} - Starting", currentIteration);
            logger.info("═".repeat(60) + "\n");

            try {
                // PHASE 1: Engineer runs application
                logger.info("\n[PHASE 1] ENGINEER AGENT: Rollback & Run Application");
                logger.info("─".repeat(60));

                E2EEngineerAgent.IterationResult engineerResult = engineerAgent.runIteration(currentIssues);
                iterationSummary.setEngineerResult(engineerResult);

                if (!engineerResult.getStatus().equals("COMPLETED")) {
                    logger.error("❌ Engineer Agent failed at iteration {}", currentIteration);
                    iterationSummary.setPhase1Status("FAILED");
                    break;
                }

                logger.info("✅ Engineer Agent completed rollback and application run");
                iterationSummary.setPhase1Status("COMPLETED");

                // PHASE 2: Tester monitors and verifies
                logger.info("\n[PHASE 2] TESTER AGENT: Monitor & Verify Results");
                logger.info("─".repeat(60));
                testerAgent.startMonitoring();

                // Give application a moment to complete and write logs
                Thread.sleep(2000);

                // Scan for issues
                currentIssues = testerAgent.scanForIssues();
                iterationSummary.setIssuesFound(currentIssues.size());
                iterationSummary.setTesterReport(testerAgent.generateVerificationReport());

                logger.info(iterationSummary.getTesterReport());

                if (currentIssues.isEmpty()) {
                    logger.info("\n✅ TESTER AGENT: NO ISSUES DETECTED - Test passed!");
                    iterationSummary.setPhase2Status("PASSED_NO_ISSUES");
                    finalResults.addIterationSummary(iterationSummary);
                    break; // Exit loop - all tests passed
                } else {
                    logger.info("\n⚠️  TESTER AGENT: {} issues detected", currentIssues.size());
                    iterationSummary.setPhase2Status("ISSUES_FOUND");

                    if (currentIteration < maxIterations) {
                        logger.info("Looping back to Engineer Agent for analysis and fixes...\n");
                    }
                }

                finalResults.addIterationSummary(iterationSummary);

            } catch (Exception e) {
                logger.error("Exception during iteration {}: ", currentIteration, e);
                iterationSummary.setPhase1Status("ERROR");
                iterationSummary.setError(e);
                finalResults.addIterationSummary(iterationSummary);
                break;
            }
        }

        // Final results
        if (currentIteration >= maxIterations) {
            logger.warn("\n⚠️  REACHED MAXIMUM ITERATIONS ({}) - Test incomplete", maxIterations);
            finalResults.setCompletionStatus("MAX_ITERATIONS_REACHED");
        } else if (currentIssues.isEmpty()) {
            logger.info("\n✅ SUCCESS - All requirements verified!");
            finalResults.setCompletionStatus("SUCCESS");
        } else {
            logger.error("\n❌ FAILURE - Issues remain after testing");
            finalResults.setCompletionStatus("FAILURE");
        }

        finalResults.setIterationCount(currentIteration);
        finalResults.setRemainingIssues(currentIssues.size());

        // Generate final report
        String finalReport = generateFinalReport(finalResults);
        logger.info(finalReport);
        finalResults.setFinalReport(finalReport);

        return finalResults;
    }

    /**
     * Generate comprehensive final report
     */
    private String generateFinalReport(TwoAgentTestResults results) {
        StringBuilder report = new StringBuilder();
        report.append("\n\n");
        report.append("╔═══════════════════════════════════════════════════════════╗\n");
        report.append("║          TWO-AGENT VERIFICATION - FINAL REPORT           ║\n");
        report.append("╚═══════════════════════════════════════════════════════════╝\n\n");

        report.append("SUMMARY:\n");
        report.append("─".repeat(60)).append("\n");
        report.append("Status: ").append(results.completionStatus).append("\n");
        report.append("Iterations: ").append(results.iterationCount).append("\n");
        report.append("Remaining Issues: ").append(results.remainingIssues).append("\n\n");

        report.append("ITERATION HISTORY:\n");
        report.append("─".repeat(60)).append("\n");
        for (IterationSummary summary : results.iterationSummaries) {
            report.append(String.format("\nIteration #%d:\n", summary.iterationNumber));
            report.append(String.format("  Engineer Agent: %s\n", summary.phase1Status));
            report.append(String.format("  Tester Agent: %s\n", summary.phase2Status));
            report.append(String.format("  Issues Found: %d\n", summary.issuesFound));
        }

        report.append("\n" + "═".repeat(60)).append("\n");
        report.append("END OF REPORT\n");
        report.append("═".repeat(60)).append("\n");

        return report.toString();
    }

    // Result container for two-agent test run
    public static class TwoAgentTestResults {
        private String completionStatus = "PENDING";
        private int iterationCount = 0;
        private int remainingIssues = 0;
        private List<IterationSummary> iterationSummaries = new ArrayList<>();
        private String finalReport = "";

        public void setCompletionStatus(String status) { this.completionStatus = status; }
        public void setIterationCount(int count) { this.iterationCount = count; }
        public void setRemainingIssues(int count) { this.remainingIssues = count; }
        public void setFinalReport(String report) { this.finalReport = report; }
        public void addIterationSummary(IterationSummary summary) { this.iterationSummaries.add(summary); }

        public boolean isSuccess() { return "SUCCESS".equals(completionStatus); }
        public String getStatus() { return completionStatus; }
    }

    // Per-iteration summary
    public static class IterationSummary {
        private int iterationNumber;
        private String phase1Status = "PENDING";
        private String phase2Status = "PENDING";
        private int issuesFound = 0;
        private String testerReport = "";
        private E2EEngineerAgent.IterationResult engineerResult;
        private Exception error;

        public IterationSummary(int number) { this.iterationNumber = number; }

        public void setPhase1Status(String status) { this.phase1Status = status; }
        public void setPhase2Status(String status) { this.phase2Status = status; }
        public void setIssuesFound(int count) { this.issuesFound = count; }
        public void setTesterReport(String report) { this.testerReport = report; }
        public void setEngineerResult(E2EEngineerAgent.IterationResult result) { this.engineerResult = result; }
        public void setError(Exception e) { this.error = e; }

        public String getTesterReport() { return testerReport; }
    }

    public static void main(String[] args) throws Exception {
        String spreadsheetId = System.getProperty("spreadsheet.id",
            "1calea2LBN3RqJTU2dUhFP1TzE5dGlO6s8RQiQ2_GUo8");
        String csvPath = System.getProperty("csv.path",
            "src/test/resources/test_transactions.csv");
        String sheetYear = System.getProperty("sheet.year", "2025");
        String sheetMonth = System.getProperty("sheet.month", "04");
        String logFilePath = System.getProperty("log.path",
            "/Users/bob/ai_projects/Transaction_processor/logs/transaction-processor.log");

        E2ETwoAgentOrchestrator orchestrator = new E2ETwoAgentOrchestrator(
            spreadsheetId, csvPath, sheetYear, sheetMonth, logFilePath
        );

        TwoAgentTestResults results = orchestrator.executeFullVerification();
        System.exit(results.isSuccess() ? 0 : 1);
    }
}
