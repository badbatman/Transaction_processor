package com.transactionprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requirement.md Compliance Verification Checklist
 * Provides systematic verification of all Requirement.md requirements
 *
 * Each requirement from Requirement.md is mapped to a verification check
 */
public class E2ERequirementChecklist {
    private static final Logger logger = LoggerFactory.getLogger(E2ERequirementChecklist.class);

    private List<RequirementCheck> checks = new ArrayList<>();

    public E2ERequirementChecklist() {
        initializeChecklist();
    }

    /**
     * Initialize all requirement checks from Requirement.md
     */
    private void initializeChecklist() {
        // Section: Buy Transaction Processing (Lines 71-83)
        checks.add(new RequirementCheck(
            "BUY_ROW_INSERTION",
            "Requirement.md:71-76",
            "Buy transaction: Find last valid row and insert new row if needed"
        ));
        checks.add(new RequirementCheck(
            "BUY_FIELD_MAPPING",
            "Requirement.md:77-81",
            "Buy transaction: Update C(date), D(price), E(quantity), F(fee), R(description)"
        ));
        checks.add(new RequirementCheck(
            "BUY_FORMATTING",
            "Requirement.md:82",
            "Buy transaction: Mark new row as Bold+Italic"
        ));
        checks.add(new RequirementCheck(
            "BUY_FORMULA_PRESERVATION",
            "Requirement.md:75",
            "Buy transaction: Preserve and adjust formulas in columns J-Q"
        ));

        // Section: Sell Transaction Processing (Lines 84-102)
        checks.add(new RequirementCheck(
            "SELL_QUANTITY_MATCHING",
            "Requirement.md:87-90",
            "Sell transaction: Match complete quantity or combining multiple rows"
        ));
        checks.add(new RequirementCheck(
            "SELL_ROW_SPLITTING",
            "Requirement.md:90",
            "Sell transaction: Split rows when quantity is partial"
        ));
        checks.add(new RequirementCheck(
            "SELL_FIELD_MAPPING",
            "Requirement.md:92-96",
            "Sell transaction: Update G(date), H(price), I(fee-first row only), R(description)"
        ));
        checks.add(new RequirementCheck(
            "SELL_FEE_ALLOCATION",
            "Requirement.md:95",
            "Sell transaction: Fee only on first row, ZERO on other rows"
        ));
        checks.add(new RequirementCheck(
            "SELL_FORMATTING",
            "Requirement.md:97",
            "Sell transaction: Mark row as Strikethrough+Bold+Italic"
        ));
        checks.add(new RequirementCheck(
            "SELL_FORMULA_PRESERVATION",
            "Requirement.md:91",
            "Sell transaction: Preserve and adjust formulas in columns J-Q"
        ));

        // Section: Dividend Transaction Processing (Lines 105-122)
        checks.add(new RequirementCheck(
            "DIVIDEND_ROW_INSERTION",
            "Requirement.md:107-109",
            "Dividend transaction: Find last valid row and insert new row if needed"
        ));
        checks.add(new RequirementCheck(
            "DIVIDEND_CALCULATION",
            "Requirement.md:110-112",
            "Dividend: originalShares=1, dividendPerShare=amount (simplified formula)"
        ));
        checks.add(new RequirementCheck(
            "DIVIDEND_FIELD_MAPPING",
            "Requirement.md:113-120",
            "Dividend: Map C(date), D(0), E(1), G(date), H(amount), R(派息)"
        ));
        checks.add(new RequirementCheck(
            "DIVIDEND_FORMATTING",
            "Requirement.md:121",
            "Dividend: Mark row as Strikethrough+Bold+Italic"
        ));
        checks.add(new RequirementCheck(
            "DIVIDEND_FORMULA_PRESERVATION",
            "Requirement.md:108",
            "Dividend: Preserve and adjust formulas in columns J-Q"
        ));

        // Section: Data Validation (Lines 33-36)
        checks.add(new RequirementCheck(
            "INVALID_RECORD_IDENTIFICATION",
            "Requirement.md:34-36",
            "Invalid records: Identified by strikethrough only (not bold+italic alone)"
        ));
        checks.add(new RequirementCheck(
            "CODE_LABEL_MAPPING",
            "Requirement.md:49-51",
            "Code mapping: Use label from mapping file, or code if not found"
        ));

        // Section: Error Handling (Lines 140-145)
        checks.add(new RequirementCheck(
            "DUPLICATE_DETECTION",
            "Requirement.md:143",
            "Duplicate detection: Check label, date, price, fee, type (5 fields)"
        ));
        checks.add(new RequirementCheck(
            "DUPLICATE_FEE_NORMALIZATION",
            "Requirement.md:143",
            "Duplicate detection: Normalize NULL fees to ZERO for comparison"
        ));
        checks.add(new RequirementCheck(
            "ERROR_REPORTING",
            "Requirement.md:145",
            "Report all unprocessed transactions with error details"
        ));

        // Section: Verification (Lines 123-128)
        checks.add(new RequirementCheck(
            "INDEPENDENT_VERIFICATION",
            "Requirement.md:123-128",
            "Independent verification: Audit CSV against actual sheet results"
        ));

        // Section: Google Sheets Structure (Lines 15-31)
        checks.add(new RequirementCheck(
            "SHEET_STRUCTURE",
            "Requirement.md:15-31",
            "Sheet structure: A-T columns (20 columns) with correct headers"
        ));
        checks.add(new RequirementCheck(
            "COLUMN_MAPPING",
            "Requirement.md:18-31",
            "Column mapping: A=Label, B=Domain, C=Date, ... R=Description, S=Region, T=Reserved"
        ));

        // Section: CSV Processing (Lines 41-46)
        checks.add(new RequirementCheck(
            "CSV_FORMAT",
            "Requirement.md:41-46",
            "CSV format: Proper header detection and field parsing"
        ));
        checks.add(new RequirementCheck(
            "DATE_FORMAT_SUPPORT",
            "Requirement.md:44",
            "Date formats: Support yyyy/M/d, yyyy-M-d, yyyy/MM/dd, yyyy-MM-dd"
        ));
        checks.add(new RequirementCheck(
            "CURRENCY_SYMBOL_SUPPORT",
            "Requirement.md:45",
            "Currency symbols: Support ¥, HK$, $; and thousand separators"
        ));

        // API Requirements (Lines 131-139)
        checks.add(new RequirementCheck(
            "API_BATCH_PROCESSING",
            "Requirement.md:136",
            "API: Use batch operations to reduce API call count"
        ));
        checks.add(new RequirementCheck(
            "API_RETRY_MECHANISM",
            "Requirement.md:137",
            "API: Implement retry mechanism for timeouts/rate limits (max 3 attempts)"
        ));
        checks.add(new RequirementCheck(
            "API_RATE_LIMITING",
            "Requirement.md:138",
            "API: Respect 60 TPM rate limit"
        ));
    }

    /**
     * Run all verification checks
     */
    public VerificationResultSummary runAllChecks() {
        logger.info("\n╔════════════════════════════════════════════════════════╗");
        logger.info("║   REQUIREMENT.MD COMPLIANCE VERIFICATION CHECKLIST    ║");
        logger.info("╚════════════════════════════════════════════════════════╝\n");

        VerificationResultSummary summary = new VerificationResultSummary();

        // Map requirements by category
        Map<String, List<RequirementCheck>> byCategory = new LinkedHashMap<>();
        for (RequirementCheck check : checks) {
            String category = extractCategory(check.requirementRef);
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(check);
        }

        // Execute checks by category
        for (Map.Entry<String, List<RequirementCheck>> category : byCategory.entrySet()) {
            logger.info("\n[{}] {} requirements", category.getKey(), category.getValue().size());
            logger.info("─".repeat(56));

            for (RequirementCheck check : category.getValue()) {
                // For now, mark all as pending (ready to implement)
                check.status = "PENDING";
                logger.info("  ⏳ {} - {}", check.checkId, check.description);
            }

            summary.addCategory(category.getKey(), category.getValue().size());
        }

        summary.totalChecks = checks.size();
        summary.pendingChecks = checks.size();

        logger.info("\n" + "═".repeat(56));
        logger.info("TOTAL REQUIREMENTS TO VERIFY: {}", checks.size());
        logger.info("═".repeat(56) + "\n");

        return summary;
    }

    /**
     * Mark a check as passed
     */
    public void markPassed(String checkId) {
        for (RequirementCheck check : checks) {
            if (check.checkId.equals(checkId)) {
                check.status = "✅ PASSED";
                logger.info("✅ VERIFIED: {} - {}", checkId, check.description);
                return;
            }
        }
        logger.warn("⚠️  Check not found: {}", checkId);
    }

    /**
     * Mark a check as failed
     */
    public void markFailed(String checkId, String reason) {
        for (RequirementCheck check : checks) {
            if (check.checkId.equals(checkId)) {
                check.status = "❌ FAILED: " + reason;
                logger.error("❌ FAILED: {} - {}", checkId, reason);
                return;
            }
        }
        logger.warn("⚠️  Check not found: {}", checkId);
    }

    /**
     * Generate compliance report
     */
    public String generateComplianceReport() {
        // Group by status
        long passed = checks.stream().filter(c -> c.status.contains("PASSED")).count();
        long failed = checks.stream().filter(c -> c.status.contains("FAILED")).count();
        long pending = checks.stream().filter(c -> c.status.contains("PENDING")).count();

        StringBuilder report = new StringBuilder();
        report.append("\n╔════════════════════════════════════════════════════════╗\n");
        report.append("║     REQUIREMENT.MD COMPLIANCE VERIFICATION REPORT    ║\n");
        report.append("╚════════════════════════════════════════════════════════╝\n\n");

        report.append("SUMMARY:\n");
        report.append("─".repeat(56)).append("\n");
        report.append(String.format("  ✅ Passed:  %2d / %d\n", passed, checks.size()));
        report.append(String.format("  ❌ Failed:  %2d / %d\n", failed, checks.size()));
        report.append(String.format("  ⏳ Pending: %2d / %d\n", pending, checks.size()));

        double percentage = checks.isEmpty() ? 0 : (passed * 100.0) / checks.size();
        report.append(String.format("\n  COMPLIANCE: %.1f%%\n", percentage));

        if (failed > 0) {
            report.append("\nFAILED REQUIREMENTS:\n");
            report.append("─".repeat(56)).append("\n");
            checks.stream()
                .filter(c -> c.status.contains("FAILED"))
                .forEach(c -> report.append("  ").append(c.toString()).append("\n"));
        }

        return report.toString();
    }

    private String extractCategory(String ref) {
        if (ref.contains("71") || ref.contains("76")) return "Buy Transactions";
        if (ref.contains("84") || ref.contains("102")) return "Sell Transactions";
        if (ref.contains("105") || ref.contains("122")) return "Dividend Transactions";
        if (ref.contains("143")) return "Duplicate Detection";
        if (ref.contains("33") || ref.contains("36")) return "Data Validation";
        if (ref.contains("40") || ref.contains("46")) return "CSV Processing";
        if (ref.contains("131") || ref.contains("139")) return "API Requirements";
        if (ref.contains("123") || ref.contains("128")) return "Verification";
        return "Other";
    }

    // Check definition
    private static class RequirementCheck {
        String checkId;
        String requirementRef;
        String description;
        String status = "PENDING";

        RequirementCheck(String id, String ref, String desc) {
            this.checkId = id;
            this.requirementRef = ref;
            this.description = desc;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (%s): %s", checkId, status, requirementRef, description);
        }
    }

    // Summary result
    public static class VerificationResultSummary {
        int totalChecks = 0;
        int passedChecks = 0;
        int failedChecks = 0;
        int pendingChecks = 0;
        Map<String, Integer> categoryCount = new LinkedHashMap<>();

        void addCategory(String category, int count) {
            categoryCount.put(category, count);
        }

        public int getCompliancePercentage() {
            if (totalChecks == 0) return 0;
            return (passedChecks * 100) / totalChecks;
        }

        public boolean isFullyCompliant() {
            return failedChecks == 0 && pendingChecks == 0;
        }
    }

    public static void main(String[] args) {
        E2ERequirementChecklist checklist = new E2ERequirementChecklist();
        VerificationResultSummary summary = checklist.runAllChecks();
        logger.info(checklist.generateComplianceReport());
    }
}
