package com.transactionprocessor;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.service.CsvOutputService;
import com.transactionprocessor.service.CsvParserService;

/** Command-line entry point for monthly local CSV processing. */
public final class CsvApplication {
    private static final Logger logger = LoggerFactory.getLogger(CsvApplication.class);
    private static final Pattern INPUT_FILE = Pattern.compile("TR_(\\d{4})(\\d{2})\\.csv");

    private CsvApplication() {}

    public static void main(String[] args) {
        try {
            String[] inputFiles = args.length == 0 ? discoverInputFiles(Paths.get("."), Thread.currentThread().getContextClassLoader()) : args;
            if (inputFiles.length == 0) {
                throw new IllegalStateException("No TR_YYYYMM.csv input files found");
            }

            CsvOutputService outputService = new CsvOutputService(
                new CsvParserService(), Paths.get("code_label_mapping.txt"));
            for (String inputFile : inputFiles) {
                processFile(outputService, Paths.get(inputFile));
            }
        } catch (Exception e) {
            logger.error("CSV processing failed", e);
            System.exit(1);
        }
    }

    static String[] discoverInputFiles(Path workingDirectory, ClassLoader classLoader) throws IOException {
        Set<String> discovered = new LinkedHashSet<>();

        addDiscoveredFiles(workingDirectory, discovered);
        addDiscoveredFiles(workingDirectory.resolve("src"), discovered);
        addDiscoveredFiles(workingDirectory.resolve("src").resolve("main").resolve("resources"), discovered);
        addDiscoveredFiles(workingDirectory.resolve("target").resolve("classes"), discovered);
        addDiscoveredClasspathFiles(classLoader, discovered);

        return discovered.stream()
            .sorted(Comparator.naturalOrder())
            .toArray(String[]::new);
    }

    private static void addDiscoveredFiles(Path directory, Set<String> discovered) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .map(Path::toString)
                .filter(path -> INPUT_FILE.matcher(Path.of(path).getFileName().toString()).matches())
                .forEach(discovered::add);
        }
    }

    private static void addDiscoveredClasspathFiles(ClassLoader classLoader, Set<String> discovered) throws IOException {
        if (classLoader == null) {
            return;
        }

        Enumeration<URL> resources = classLoader.getResources("");
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            addDiscoveredClasspathUrl(resource, discovered);
        }
    }

    private static void addDiscoveredClasspathUrl(URL resource, Set<String> discovered) throws IOException {
        String protocol = resource.getProtocol();
        if ("file".equals(protocol)) {
            try {
                Path path = Paths.get(resource.toURI());
                addDiscoveredFiles(path, discovered);
            } catch (URISyntaxException e) {
                throw new IOException("Unable to resolve classpath resource: " + resource, e);
            }
            return;
        }

        if (!"jar".equals(protocol)) {
            return;
        }

        try {
            JarURLConnection connection = (JarURLConnection) resource.openConnection();
            try (JarFile jarFile = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    int lastSlash = name.lastIndexOf('/');
                    String fileName = lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
                    if (INPUT_FILE.matcher(fileName).matches()) {
                        discovered.add(fileName);
                    }
                }
            }
        } catch (Exception ignored) {
            logger.debug("Ignoring non-file classpath resource {}", resource, ignored);
        }
    }

    private static void processFile(CsvOutputService outputService, Path inputFile) throws IOException {
        Path resolvedInputFile = resolveInputPath(inputFile);
        Matcher matcher = INPUT_FILE.matcher(resolvedInputFile.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Input filename must match TR_YYYYMM.csv: " + inputFile);
        }

        String yearMonthText = matcher.group(1) + matcher.group(2);
        YearMonth month = YearMonth.parse(matcher.group(1) + "-" + matcher.group(2));
        Path outputFile = Paths.get("target", "Processed_" + yearMonthText + ".csv");
        Path reportFile = Paths.get("target", "Processed_" + yearMonthText + "_report.csv");
        CsvOutputService.Summary summary = outputService.process(resolvedInputFile, month, outputFile, reportFile);

        System.out.printf("Processed %s: %d input transactions, %d output rows, %d duplicates, %d failures, %d unmatched sells, %dms%n",
            resolvedInputFile, summary.inputTransactions(), summary.outputRecords(), summary.duplicates(),
            summary.failures(), summary.unmatchedSells(), summary.processingMillis());
        System.out.printf("Output: %s%nReport: %s%n", summary.outputFile(), summary.reportFile());
    }

    private static Path resolveInputPath(Path inputFile) {
        if (inputFile == null) {
            throw new IllegalArgumentException("Input file path is null");
        }

        Path candidate = inputFile.toAbsolutePath().normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }

        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        for (Path root : new Path[] {
            workingDirectory,
            workingDirectory.resolve("src"),
            workingDirectory.resolve("src").resolve("main").resolve("resources"),
            workingDirectory.resolve("target")
        }) {
            Path resolved = root.resolve(inputFile).normalize();
            if (Files.exists(resolved)) {
                return resolved.toAbsolutePath().normalize();
            }
        }

        return candidate;
    }
}