# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Project Overview
Java 21 Maven application processing stock transactions from CSV files and syncing to Google Sheets.

## Build/Test Commands
```bash
# Compile
mvn compile

# Run tests
mvn test

# Run single test class
mvn test -Dtest=BuyTransactionProcessorTest

# Run single test method
mvn test -Dtest=BuyTransactionProcessorTest#testProcessTransaction_NewBuyRow

# Package with shade plugin (creates fat JAR)
mvn package

# Run application
mvn exec:java -Dexec.mainClass="com.transactionprocessor.MainApplication"
# Or
java -jar target/transaction-processor-1.0.0.jar
```

## Project-Specific Patterns

### Testing: Fake Pattern (Not Mockito)
Tests extend `GoogleSheetsService` with inner `FakeSheets` class rather than using mocks. This is the canonical pattern:
```java
private static class FakeSheets extends GoogleSheetsService {
    public FakeSheets(GoogleSheetsConfig cfg, Sheets s) { super(cfg, s); }
    @Override public List<GoogleSheetsRow> readSheet(...) { return rowsToReturn; }
}
```
See: [`BuyTransactionProcessorTest.java`](src/test/java/com/transactionprocessor/processor/BuyTransactionProcessorTest.java:1)

### Rate Limiting
GoogleSheetsService has built-in 50 requests/minute rate limiting with exponential backoff. Don't add additional rate limiting.

### Code-Label Mapping
Stock codes map to display labels via [`code_label_mapping.txt`](code_label_mapping.txt:1) (classpath resource), NOT from Google Sheets. Format: `CODE=Label`.

### Dry-Run Mode
Set `app.dry.run=true` in [`application.properties`](src/main/resources/application.properties:7) to write JSON preview to `target/` instead of making live API calls.

### Credentials Resolution
credentials.json is searched in this order: classpath resource → file system root.

### Transaction Formatting Rules
- **BUY**: Bold + Italic (new rows), copies formula columns from existing rows
- **SELL**: Strikethrough (marks as closed)
- **DIVIDEND**: Updates close price and close date on matching position

### CSV File Naming
Pattern: `Transaction records(SheetName)_YYYYMM.csv` (e.g., `Transaction records(Alpha)_202601.csv`)

### E2E Test Utilities
- `PreTestBackupUtility` - Creates initial backups before any testing
- `RollbackUtility` / `RollbackToInitialUtility` - Restores sheet state
- `E2ETestRunner` - Orchestrates full test lifecycle with verification

### Logging
- Main logs: `logs/transaction-processor.log`
- Error logs: `logs/transaction-processor-error.log`
- Performance logs: `logs/transaction-processor-performance.log`
- All rotate at 10MB with 30-day history

### Required rowNumber Field
`GoogleSheetsRow.setRowNumber()` MUST be called in tests to avoid NPE during formula column copying.
