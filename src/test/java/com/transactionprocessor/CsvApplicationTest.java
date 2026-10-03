package com.transactionprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CsvApplicationTest {

    @Test
    void discoverInputFilesIncludesWorkingDirectoryAndClasspath() throws Exception {
        Path tempDir = Files.createTempDirectory("csv-app-discovery");
        Files.writeString(tempDir.resolve("TR_202501.csv"), "dummy");

        Path classpathDir = tempDir.resolve("classpath-root");
        Files.createDirectories(classpathDir);
        Files.writeString(classpathDir.resolve("TR_202502.csv"), "dummy");

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[] { classpathDir.toUri().toURL() },
                CsvApplicationTest.class.getClassLoader())) {
            String[] files = CsvApplication.discoverInputFiles(tempDir, classLoader);

            assertThat(files)
                .extracting(path -> Path.of(path).getFileName().toString())
                .containsExactly("TR_202501.csv", "TR_202502.csv");
        }
    }
}
