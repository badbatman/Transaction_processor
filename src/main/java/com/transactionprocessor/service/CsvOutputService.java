package com.transactionprocessor.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;

import com.transactionprocessor.model.Transaction;
import com.transactionprocessor.model.TransactionType;

/** Generates a local A-T portfolio CSV without connecting to Google Sheets. */
public class CsvOutputService {
    private static final String[] OUTPUT_HEADER = {
        "Label", "Domain", "Open time", "Open price", "Number of stock", "Open Fee + Tax",
        "Close time", "Close price", "Close Fee + Tax", "J", "K", "L", "M", "N", "O", "P", "Q",
        "Description", "Region", "Pools"
    };

    private static final Comparator<Transaction> TRANSACTION_ORDER = Comparator
        .comparing(Transaction::getDate)
        .thenComparingInt(Transaction::getLineNumber);

    private final CsvParserService csvParserService;
    private final Path mappingFile;

    public CsvOutputService(CsvParserService csvParserService, Path mappingFile) {
        this.csvParserService = csvParserService;
        this.mappingFile = mappingFile;
    }

    public Summary process(Path inputFile, YearMonth month, Path outputFile, Path reportFile) throws IOException {
        long started = System.nanoTime();
        List<Transaction> allTransactions = csvParserService.parseCsvFile(inputFile);
        Mapping mapping = loadMapping();
        List<Transaction> transactions = allTransactions.stream()
            .filter(transaction -> transaction.getDate() != null && !transaction.getDate().isAfter(month.atEndOfMonth()))
            .sorted(TRANSACTION_ORDER)
            .toList();

        List<OutputRecord> outputRecords = new ArrayList<>();
        List<ReportEntry> reportEntries = new ArrayList<>();
        List<Lot> lots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        int failures = 0;
        int unmatched = 0;
        long sequence = 0;

        for (Transaction transaction : transactions) {
            boolean inMonth = YearMonth.from(transaction.getDate()).equals(month);
            if (!isValid(transaction)) {
                if (inMonth) {
                    failures++;
                    reportEntries.add(new ReportEntry("FAILED", transaction, "Invalid required values or negative fee"));
                }
                continue;
            }

            Instrument instrument = mapping.find(transaction);
            String market = classifyMarket(transaction, instrument);
            String identity = identity(transaction);

            if (transaction.getType() != TransactionType.DIVIDEND) {
                String duplicateKey = String.join("|", identity, transaction.getDate().toString(),
                    transaction.getType().name(), decimalKey(transaction.getPrice()), decimalKey(transaction.calculateFee()));
                if (!seen.add(duplicateKey)) {
                    if (inMonth) {
                        duplicates++;
                        reportEntries.add(new ReportEntry("DUPLICATE", transaction, "Duplicate non-dividend transaction"));
                    }
                    continue;
                }
            }

            switch (transaction.getType()) {
                case BUY -> lots.add(new Lot(transaction, instrument, market, identity));
                case SELL -> {
                    BigDecimal remaining = transaction.getQuantity();
                    BigDecimal feeRemaining = transaction.calculateFee();
                    for (Lot lot : lots) {
                        if (remaining.signum() <= 0) {
                            break;
                        }
                        if (!lot.identity.equals(identity) || lot.remaining.signum() <= 0) {
                            continue;
                        }
                        BigDecimal lotQuantityBeforeMatch = lot.remaining;
                        BigDecimal matched = lotQuantityBeforeMatch.min(remaining);
                        BigDecimal appliedBuyFee = lot.feeRemaining.multiply(matched)
                            .divide(lotQuantityBeforeMatch, 12, RoundingMode.HALF_UP);
                        lot.feeRemaining = lot.feeRemaining.subtract(appliedBuyFee);
                        lot.remaining = lot.remaining.subtract(matched);
                        remaining = remaining.subtract(matched);
                        BigDecimal appliedFee = feeRemaining;
                        feeRemaining = BigDecimal.ZERO;
                        if (inMonth) {
                            outputRecords.add(new OutputRecord(market, TransactionType.SELL, sequence++,
                                positionRow(lot.purchase, lot.instrument, matched, appliedBuyFee,
                                    transaction, appliedFee)));
                        }
                    }
                    if (inMonth && remaining.signum() > 0) {
                        unmatched++;
                        reportEntries.add(new ReportEntry("UNMATCHED_SELL", transaction,
                            "Unmatched sell quantity: " + number(remaining)));
                    }
                }
                case DIVIDEND -> {
                    if (inMonth) {
                        outputRecords.add(new OutputRecord(market, TransactionType.DIVIDEND, sequence++,
                            dividendRow(transaction, instrument)));
                    }
                }
            }
        }

        for (Lot lot : lots) {
            if (YearMonth.from(lot.purchase.getDate()).equals(month) && lot.remaining.signum() > 0) {
                outputRecords.add(new OutputRecord(lot.market, TransactionType.BUY, sequence++,
                    positionRow(lot.purchase, lot.instrument, lot.remaining, lot.feeRemaining,
                        null, BigDecimal.ZERO)));
            }
        }

        outputRecords.sort(Comparator.comparingInt((OutputRecord record) -> marketOrder(record.market))
            .thenComparingInt(record -> typeOrder(record.type))
            .thenComparingLong(record -> record.sequence));
        writeOutput(outputFile, outputRecords);
        writeReport(reportFile, reportEntries);

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        return new Summary(allTransactions.size(), outputRecords.size(), duplicates, failures, unmatched,
            outputFile, reportFile, elapsedMillis);
    }

    private boolean isValid(Transaction transaction) {
        if (transaction.getName() == null || transaction.getName().isBlank()
                || transaction.getCode() == null || transaction.getCode().isBlank()
                || transaction.getType() == null || transaction.getDate() == null
                || transaction.getPrice() == null || transaction.getQuantity() == null
                || transaction.getAmount() == null || transaction.getDate().isAfter(LocalDate.now())
                || transaction.calculateFee().signum() < 0) {
            return false;
        }
        if (transaction.getType() == TransactionType.DIVIDEND) {
            return transaction.getAmount() != null;
        }
        return transaction.getPrice().signum() > 0 && transaction.getQuantity().signum() > 0;
    }

    private List<String> positionRow(Transaction purchase, Instrument instrument, BigDecimal quantity,
                                     BigDecimal purchaseFee, Transaction sale, BigDecimal sellFee) {
        List<String> row = blankRow();
        row.set(0, instrument.label(purchase));
        row.set(1, instrument.domain);
        row.set(2, date(purchase.getDate()));
        row.set(3, number(purchase.getPrice()));
        row.set(4, number(quantity));
        row.set(5, number(purchaseFee));
        if (sale != null) {
            row.set(6, date(sale.getDate()));
            row.set(7, number(sale.getPrice()));
            row.set(8, number(sellFee));
        }
        String description = joinText(purchase.getDescription(), purchase.getRemarks(),
            sale == null ? null : sale.getRemarks());
        if (sale != null) {
            String closeSummary = String.join(",",
                date(sale.getDate()),
                number(sale.getPrice()),
                number(quantity),
                number(purchaseFee));
            description = description == null || description.isBlank()
                ? closeSummary
                : description + " " + closeSummary;
        }
        row.set(17, description);
        row.set(18, instrument.outputRegion);
        row.set(19, instrument.pool);
        return row;
    }

    private List<String> dividendRow(Transaction transaction, Instrument instrument) {
        List<String> row = blankRow();
        BigDecimal dividendFee = transaction.calculateFee();
        row.set(0, instrument.label(transaction));
        row.set(1, instrument.domain);
        row.set(2, date(transaction.getDate()));
        row.set(3, "0");
        row.set(4, "1");
        row.set(5, "0");
        row.set(6, date(transaction.getDate()));
        row.set(7, number(transaction.getAmount().add(dividendFee)));
        row.set(8, number(dividendFee));
        row.set(17, "派息");
        row.set(18, instrument.outputRegion);
        row.set(19, instrument.pool);
        return row;
    }

    private Mapping loadMapping() throws IOException {
        Mapping mapping = new Mapping();
        if (mappingFile == null || !Files.exists(mappingFile)) {
            return mapping;
        }
        try (BufferedReader reader = Files.newBufferedReader(mappingFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            for (CSVRecord record : parser) {
                if (record.size() == 1 && record.get(0).contains("=")) {
                    String[] parts = record.get(0).split("=", 2);
                    mapping.put(parts[0], new Instrument(parts[1], "", "", "", "", ""));
                } else if (record.size() >= 2) {
                    String code = record.get(0).trim();
                    String label = record.get(1).trim();
                    String domain = record.size() > 2 ? record.get(2).trim() : "";
                    String region = record.size() > 3 ? record.get(3).trim() : "";
                    String pool = record.size() > 4 ? record.get(4).trim() : "";
                    Instrument instrument = new Instrument(label, domain, region, pool,
                        label.toUpperCase(Locale.ROOT).startsWith("HKG") ? label : "", region);
                    if (code.isBlank()) {
                        mapping.putName(label, instrument);
                        mapping.put(label, instrument);
                    } else {
                        mapping.put(code, instrument);
                    }
                }
            }
        }
        return mapping;
    }

    private String classifyMarket(Transaction transaction, Instrument instrument) {
        String pool = instrument.pool.toUpperCase(Locale.ROOT);
        if (pool.equals("MPF") || (instrument.label != null && instrument.label.toUpperCase(Locale.ROOT).contains("MPF"))) {
            return "UNKNOWN";
        }
        String code = normalizeCode(transaction.getCode());
        String canonicalCode = normalizeCode(instrument.canonicalCode);
        String region = instrument.region.toLowerCase(Locale.ROOT);
        if (region.contains("中国")) {
            return (code.startsWith("HKG") || canonicalCode.startsWith("HKG") || code.matches("\\d{5}"))
                ? "HK" : "A";
        }
        if (region.contains("美国") || region.equals("us") || region.equals("usa")) {
            return code.startsWith("HKG") ? "UNKNOWN" : "US";
        }
        if (code.startsWith("HKG") || code.matches("\\d{5}")) {
            return "HK";
        }
        if (code.startsWith("SH") || code.startsWith("SZ")) {
            return "A";
        }
        if (code.matches("[A-Z]{1,5}") || code.matches("[A-Z]{1,5}[.-][A-Z0-9]{1,4}")) {
            return "US";
        }
        return "UNKNOWN";
    }

    private void writeOutput(Path outputFile, List<OutputRecord> records) throws IOException {
        createParent(outputFile);
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setQuoteMode(QuoteMode.MINIMAL)
            .setIgnoreSurroundingSpaces(true)
            .setRecordSeparator("\n")
            .build();
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, format)) {
            printer.printRecord(normalizeGoogleSheetsRow(OUTPUT_HEADER));
            for (OutputRecord record : records) {
                printer.printRecord(normalizeGoogleSheetsRow(record.values));
            }
        }
    }

    private void writeReport(Path reportFile, List<ReportEntry> entries) throws IOException {
        createParent(reportFile);
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setQuoteMode(QuoteMode.MINIMAL)
            .setIgnoreSurroundingSpaces(true)
            .setRecordSeparator("\n")
            .build();
        try (BufferedWriter writer = Files.newBufferedWriter(reportFile, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, format)) {
            printer.printRecord(normalizeGoogleSheetsRow(List.of("Status", "Input line", "Code", "Name", "Details", "Original record")));
            for (ReportEntry entry : entries) {
                printer.printRecord(normalizeGoogleSheetsRow(List.of(
                    entry.status,
                    String.valueOf(entry.transaction.getLineNumber()),
                    entry.transaction.getCode(),
                    entry.transaction.getName(),
                    entry.details,
                    entry.transaction.getRawLine())));
            }
        }
    }

    private static List<String> normalizeGoogleSheetsRow(String[] values) {
        return normalizeGoogleSheetsRow(java.util.Arrays.asList(values));
    }

    private static List<String> normalizeGoogleSheetsRow(List<String> values) {
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            normalized.add(normalizeGoogleSheetsValue(value));
        }
        return normalized;
    }

    private static String normalizeGoogleSheetsValue(String value) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        String noLineBreaks = trimmed.replace("\r", "").replace("\n", " ");
        if (noLineBreaks.startsWith("=") || noLineBreaks.startsWith("+") || noLineBreaks.startsWith("-")) {
            return "'" + noLineBreaks;
        }

        if (noLineBreaks.matches("[+-]?\\d+(?:\\.\\d+)?")) {
            return new BigDecimal(noLineBreaks).stripTrailingZeros().toPlainString();
        }

        if (noLineBreaks.matches("[+-]?\\d+(?:\\.\\d+)?[Ee][+-]?\\d+")) {
            return new BigDecimal(noLineBreaks).stripTrailingZeros().toPlainString();
        }

        return noLineBreaks.replace("\u00A0", "").replace("\u202F", "");
    }

    private static void createParent(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static List<String> blankRow() {
        List<String> values = new ArrayList<>(20);
        for (int column = 0; column < 20; column++) {
            values.add("");
        }
        return values;
    }

    private static String identity(Transaction transaction) {
        String code = normalizeCode(transaction.getCode());
        return code.isEmpty() ? "NAME:" + normalizeCode(transaction.getName()) : "CODE:" + code;
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\\"']", "").toUpperCase(Locale.ROOT);
    }

    private static String decimalKey(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : decimalKey(value);
    }

    private static String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private static String joinText(String... values) {
        List<String> nonBlank = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                nonBlank.add(value.trim());
            }
        }
        return String.join(" ", nonBlank);
    }

    private static int marketOrder(String market) {
        return switch (market) {
            case "A" -> 0;
            case "HK" -> 1;
            case "US" -> 2;
            default -> 3;
        };
    }

    private static int typeOrder(TransactionType type) {
        return switch (type) {
            case BUY -> 0;
            case SELL -> 1;
            case DIVIDEND -> 2;
        };
    }

    public record Summary(int inputTransactions, int outputRecords, int duplicates,
                          int failures, int unmatchedSells, Path outputFile, Path reportFile,
                          long processingMillis) {}

    private record OutputRecord(String market, TransactionType type, long sequence, List<String> values) {}
    private record ReportEntry(String status, Transaction transaction, String details) {}

    private static final class Lot {
        private final Transaction purchase;
        private final Instrument instrument;
        private final String market;
        private final String identity;
        private BigDecimal remaining;
        private BigDecimal feeRemaining;

        private Lot(Transaction purchase, Instrument instrument, String market, String identity) {
            this.purchase = purchase;
            this.instrument = instrument;
            this.market = market;
            this.identity = identity;
            this.remaining = purchase.getQuantity();
            this.feeRemaining = purchase.calculateFee();
        }
    }

    private static final class Instrument {
        private final String label;
        private final String domain;
        private final String region;
        private final String pool;
        private final String canonicalCode;
        private final String outputRegion;

        private Instrument(String label, String domain, String region, String pool,
                           String canonicalCode, String outputRegion) {
            this.label = label == null ? "" : label;
            this.domain = domain == null ? "" : domain;
            this.region = region == null ? "" : region;
            this.pool = pool == null ? "" : pool;
            this.canonicalCode = canonicalCode == null ? "" : canonicalCode;
            this.outputRegion = this.region.isBlank() ? "Unknown" : this.region;
        }

        private String label(Transaction transaction) {
            if (label.isBlank()) {
                return transaction.getName();
            }
            return label.equalsIgnoreCase(transaction.getCode()) ? transaction.getCode() : label;
        }
    }

    private static final class Mapping {
        private final Map<String, Instrument> byCode = new HashMap<>();
        private final Map<String, Instrument> byName = new HashMap<>();

        private void put(String code, Instrument instrument) {
            byCode.put(normalizeCode(code), instrument);
        }

        private void putName(String name, Instrument instrument) {
            byName.put(normalizeCode(name), instrument);
        }

        private Instrument find(Transaction transaction) {
            Instrument instrument = byCode.get(normalizeCode(transaction.getCode()));
            if (instrument == null) {
                instrument = byName.get(normalizeCode(transaction.getName()));
            }
            if (instrument != null) {
                return instrument;
            }
            String code = normalizeCode(transaction.getCode());
            String region = code.startsWith("SH") || code.startsWith("SZ") ? "中国"
                : code.matches("[A-Z]{1,5}") ? "美国"
                : code.matches("\\d{5}") || code.startsWith("HKG") ? "中国" : "Unknown";
            return new Instrument("", "", region, "", "", region);
        }
    }
}