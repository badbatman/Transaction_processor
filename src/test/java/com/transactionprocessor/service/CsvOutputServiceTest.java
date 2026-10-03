package com.transactionprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvOutputServiceTest {
    @TempDir
    Path tempDir;

    private CsvOutputService outputService;

    @BeforeEach
    void setUp() {
        outputService = new CsvOutputService(new CsvParserService(), null);
    }

    @Test
    void outputsSortedTwentyColumnRowsAndReportsUnmatchedSell() throws IOException {
        Path mapping = tempDir.resolve("code_label_mapping.txt");
        Files.writeString(mapping,
            "SH600001,Alpha,Technology,中国,Investable Assets\n"
            + "02800,HKG:2800,Index,中国,Investable Assets\n"
            + "AAA,AAA,Income,美国,Investable Assets\n");
        outputService = new CsvOutputService(new CsvParserService(), mapping);

        Path input = tempDir.resolve("TR_202512.csv");
        Files.writeString(input, "交易记录\n"
            + "名称,代码,类型,日期,成交价,数量,金额,说明,备注\n"
            + "Alpha,SH600001,买入,2025/11/20,10,100,1000,,fee=5\n"
            + "Alpha,SH600001,卖出,2025/12/02,12,50,600,,exit\n"
            + "Alpha,SH600001,卖出,2025/12/03,12,150,1800,,exit\n"
            + "Alpha,SH600001,除权除息,2025/12/01,0,0,2.75,dividend,fee=0.25\n"
            + "Alpha,SH600001,除权除息,2025/12/01,0,0,3,dividend,\n"
            + "Index,\"02800\t\",买入,2025/12/03,25,10,250,,fee=1.25\n"
            + "Alpha US,AAA,买入,2025/12/04,20,5,100,,\n");

        Path output = tempDir.resolve("Processed_202512.csv");
        Path report = tempDir.resolve("report.csv");
        CsvOutputService.Summary summary = outputService.process(input, YearMonth.of(2025, 12), output, report);

        assertThat(summary.outputRecords()).isEqualTo(6);
        assertThat(summary.unmatchedSells()).isEqualTo(1);
        assertThat(summary.failures()).isZero();
        String csvText = Files.readString(output);
        assertThat(csvText).contains("2025-12-02,12,50,2.5");
        assertThat(csvText).doesNotContain("=12");
        assertThat(csvText).doesNotContain("\uFEFF");

        try (BufferedReader reader = Files.newBufferedReader(output);
             CSVParser parsed = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            List<CSVRecord> rows = parsed.getRecords();
            assertThat(rows).hasSize(6);
            assertThat(rows).allSatisfy(row -> assertThat(row.size()).isEqualTo(20));
            assertThat(rows.get(0).get("Number of stock")).isEqualTo("50");
            assertThat(rows.get(0).get("Close time")).isEqualTo("2025-12-02");
            assertThat(rows.get(0).get("Open Fee + Tax")).isEqualTo("2.5");
            assertThat(rows.get(1).get("Close time")).isEqualTo("2025-12-03");
            assertThat(rows.get(1).get("Open Fee + Tax")).isEqualTo("2.5");
            assertThat(rows.get(2).get("Description")).isEqualTo("派息");
            assertThat(rows.get(2).get("Close price")).isEqualTo("3");
            assertThat(rows.get(2).get("Close Fee + Tax")).isEqualTo("0.25");
            assertThat(rows.get(3).get("Description")).isEqualTo("派息");
            assertThat(rows.get(3).get("Close price")).isEqualTo("3");
            assertThat(rows.get(3).get("Close Fee + Tax")).isEqualTo("0");
            assertThat(rows.get(4).get("Label")).isEqualTo("HKG:2800");
            assertThat(rows.get(4).get("Open Fee + Tax")).isEqualTo("1.25");
            assertThat(rows.get(5).get("Domain")).isEqualTo("Income");
            assertThat(rows.get(5).get("Label")).isEqualTo("AAA");
            assertThat(rows.get(5).get("Region")).isEqualTo("美国");
        }
        try (BufferedReader reader = Files.newBufferedReader(report);
             CSVParser parsed = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            assertThat(parsed.getRecords()).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("Details")).contains("Unmatched sell quantity: 100");
                    assertThat(row.get("Original record")).contains("SH600001");
                });
        }
    }
}