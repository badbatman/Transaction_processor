# Transaction Processor

A Java application that converts monthly transaction-history CSV files into portfolio-table CSV output. Processing is local and does not connect to Google Sheets.

## Quick Start

### 1. Prerequisites
- Java 25 or higher
- Maven 3.6+

### 2. Build & Run

```bash
# Run selected month files
mvn exec:java -Dexec.mainClass=com.transactionprocessor.CsvApplication -Dexec.args="TR_202512.csv TR_202601.csv"

# With no arguments, process all TR_YYYYMM.csv files in the working directory
mvn exec:java -Dexec.mainClass=com.transactionprocessor.CsvApplication
```

Each input produces `target/Processed_YYYYMM.csv` and `target/Processed_YYYYMM_report.csv`.

## Features

✅ **Multi-Market Support** - A-shares, HK-shares, US-shares  
✅ **Transaction Types** - Buy, Sell, Dividend processing  
✅ **Local CSV Output** - Writes the portfolio A-T column layout  
✅ **Market Groups** - A-share, Hong Kong, US, and Unknown  
✅ **Transaction Handling** - Buys, FIFO/partial sells, and dividends  
✅ **Fee Extraction** - Reads `fee=` from description/remarks, including standalone continuation lines  
✅ **Exception Report** - Lists unmatched sells and processing issues with input row details  
✅ **Stable Ordering** - Market, transaction type, then original transaction order  

## Project Structure

```
src/
├── main/java/com/transactionprocessor/
│   ├── CsvApplication.java            # CLI entry point
│   ├── model/                         # Transaction data models
│   └── service/                       # CSV parsing and output
└── test/java/                         # Unit tests
```

## Documentation

- **[Requirement.md](Requirement.md)** - Business requirements

## CSV File Format

Files must follow naming convention: `TR_YYYYMM.csv`

Example:
- `TR_202512.csv`
- `TR_202601.csv`

## Testing

```bash
mvn clean test
```

The CSV generation path requires no API credentials or spreadsheet configuration. The output format and processing rules are defined in [Requirement.md](Requirement.md).

## License

Private - Internal use only
