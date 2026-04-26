# Transaction Processor

A Java-based application for processing financial transaction records and synchronizing them with Google Sheets.

## Quick Start

### 1. Prerequisites
- Java 11 or higher
- Maven 3.6+
- Google Service Account credentials (credentials.json)
- Google Sheet with appropriate permissions

### 2. Configuration (Spring Boot Standard)

The application follows Spring Boot configuration conventions using `application.properties`:

**Option A: Edit Configuration File**
- Edit `src/main/resources/application.properties`
- Set `google.sheets.credentials.path=credentials.json` (default)
- Place your `credentials.json` in `src/main/resources/`

**Option B: Command Line Overrides**
```bash
java -jar target/transaction-processor.jar \
  --google.sheets.credentials-path=/path/to/creds.json \
  --default.spreadsheet.id=your-sheet-id
```

**Option C: Environment Variables**
```bash
export GOOGLE_SHEETS_CREDENTIALS_PATH=/path/to/credentials.json
export DEFAULT_SPREADSHEET_ID=your-spreadsheet-id
java -jar target/transaction-processor.jar
```

### 3. Build & Run

```bash
# Build
mvn clean package

# Run
java -jar target/transaction-processor.jar
```

## Features

✅ **Multi-Market Support** - A-shares, HK-shares, US-shares  
✅ **Transaction Types** - Buy, Sell, Dividend processing  
✅ **Google Sheets Integration** - Automatic sync with formatted output  
✅ **Dynamic Discovery** - Auto-detects sheets and months from CSV files  
✅ **Rollback Support** - Backup creation before modifications  
✅ **Configurable** - Environment-driven configuration  
✅ **Independent Verification** - Automatic compliance checking per Requirement.md  
✅ **Fee/Tax Tracking** - Extract fees from CSV remarks (fee=XX.XX format)  
✅ **Formatting Rules** - Bold+Italic for buys, Strikethrough for sells/dividends  
✅ **Rate Limiting** - Automatic throttling to stay within Google API 60 TPM limit  
✅ **Retry Mechanism** - Exponential backoff (2s, 4s, 8s, 16s, 30s) for 429 errors  
✅ **Duplicate Detection** - Prevents duplicate entries for same transaction (label + date)  
✅ **Grid Limit Handling** - Automatically appends rows when sheet grid limits are exceeded  
✅ **Append-Only Writes** - New buy/dividend transactions always appended (never overwrite existing data)  

## Project Structure

```
src/
├── main/java/com/transactionprocessor/
│   ├── MainApplication.java          # Entry point
│   ├── config/                        # Configuration classes
│   ├── model/                         # Data models
│   ├── processor/                     # Transaction processors
│   └── service/                       # Business services
└── test/java/                         # Unit & integration tests
```

## Documentation

- **[CONFIGURATION.md](CONFIGURATION.md)** - Complete configuration guide
- **[Requirement.md](Requirement.md)** - Business requirements
- **[CLEANUP_SUMMARY.md](CLEANUP_SUMMARY.md)** - Code cleanup details
- **[VERIFICATION_SERVICE_ENHANCEMENT.md](VERIFICATION_SERVICE_ENHANCEMENT.md)** - Verification mechanism details

## CSV File Format

Files must follow naming convention: `<sheetName>_YYYYMM.csv`

Example:
- `Transaction records(Alpha)_202504.csv`
- `Transaction records(Cash Flow)_202505.csv`

## Testing

```bash
mvn clean test
```

Expected result: **37 tests, 0 failures**

## Configuration Properties

### Required Properties
Set via command line, environment variable, or application.yml:

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `google.sheets.credentials-path` | `GOOGLE_SHEETS_CREDENTIALS_PATH` | `credentials.json` | Path to service account JSON |
| `default.spreadsheet.id` | `DEFAULT_SPREADSHEET_ID` | *(required)* | Target Google Sheet ID |

### Optional Properties

| Property | Default | Description |
|----------|---------|-------------|
| `app.dry-run` | `false` | Dry run mode (no writes) |
| `logging.level.root` | `INFO` | Root logging level |
| `logging.level.com.transactionprocessor` | `DEBUG` | Application logging level |
| `processing.batch-size` | `100` | Batch size for operations |

See `src/main/resources/application.yml` for complete list.

## License

Private - Internal use only
