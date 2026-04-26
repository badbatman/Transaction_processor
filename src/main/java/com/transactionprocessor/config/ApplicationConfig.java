package com.transactionprocessor.config;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Application configuration manager
 */
public class ApplicationConfig {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationConfig.class);
    private static final String CONFIG_FILE = "application.properties";
    
    private final Config config;
    
    // Google Sheets configuration
    private final String googleSheetsApplicationName;
    private final String googleSheetsCredentialsPath;
    private final String googleSheetsApiVersion;
    private final int googleSheetsRetryMaxAttempts;
    private final long googleSheetsRetryDelayMs;
    
    // CSV processing configuration
    private final String csvEncoding;
    private final List<String> csvDateFormats;
    private final String csvNumberFormats;
    
    // Processing configuration
    private final int processingBatchSize;
    private final int processingRetryMaxAttempts;
    private final long processingRetryDelayMs;
    
    // Logging configuration
    private final String loggingLevel;
    private final String loggingFileName;
    private final String loggingFileMaxSize;
    private final int loggingFileMaxHistory;
    
    // Performance configuration
    private final boolean performanceMonitoringEnabled;
    private final boolean performanceMetricsEnabled;
    private final long performanceReportingInterval;
    
    // Dry-run configuration
    private final boolean dryRun;

    public ApplicationConfig() {
        this(ConfigFactory.parseResources(CONFIG_FILE).withFallback(ConfigFactory.load()));
    }

    public ApplicationConfig(Config config) {
        this.config = config;
        
        // Google Sheets configuration
        this.googleSheetsApplicationName = config.getString("google.sheets.application.name");
        this.googleSheetsCredentialsPath = config.getString("google.sheets.credentials.path");
        this.googleSheetsApiVersion = config.getString("google.sheets.api.version");
        this.googleSheetsRetryMaxAttempts = config.getInt("google.sheets.retry.max.attempts");
        this.googleSheetsRetryDelayMs = config.getLong("google.sheets.retry.delay.ms");
        
        // CSV processing configuration
        this.csvEncoding = config.getString("csv.encoding");
        this.csvDateFormats = Arrays.asList(config.getString("csv.date.formats").split(","));
        this.csvNumberFormats = config.getString("csv.number.formats");
        
        // Processing configuration
        this.processingBatchSize = config.getInt("processing.batch.size");
        this.processingRetryMaxAttempts = config.getInt("processing.retry.max.attempts");
        this.processingRetryDelayMs = config.getLong("processing.retry.delay.ms");
        
        // Logging configuration
        this.loggingLevel = config.getString("logging.level.root");
        this.loggingFileName = config.getString("logging.file.name");
        this.loggingFileMaxSize = config.getString("logging.file.max-size");
        this.loggingFileMaxHistory = config.getInt("logging.file.max-history");
        
        // Performance configuration
        this.performanceMonitoringEnabled = config.getBoolean("performance.monitoring.enabled");
        this.performanceMetricsEnabled = config.getBoolean("performance.metrics.enabled");
        this.performanceReportingInterval = config.getLong("performance.reporting.interval");
        
        // Dry-run configuration (default: false)
        this.dryRun = config.hasPath("app.dry.run") ? config.getBoolean("app.dry.run") : false;
        
        logger.info("Application configuration loaded successfully");
        validateConfiguration();
    }

    private void validateConfiguration() {
        // Validate Google Sheets credentials file exists
        File credentialsFile = new File(googleSheetsCredentialsPath);
        if (!credentialsFile.exists()) {
            logger.warn("Google Sheets credentials file not found at: {}", googleSheetsCredentialsPath);
        }
        
        // Validate batch size
        if (processingBatchSize <= 0 || processingBatchSize > 1000) {
            throw new IllegalArgumentException("Processing batch size must be between 1 and 1000");
        }
        
        logger.debug("Configuration validation completed");
    }

    // Getters
    public String getGoogleSheetsApplicationName() { return googleSheetsApplicationName; }
    public String getGoogleSheetsCredentialsPath() { return googleSheetsCredentialsPath; }
    public String getGoogleSheetsApiVersion() { return googleSheetsApiVersion; }
    public int getGoogleSheetsRetryMaxAttempts() { return googleSheetsRetryMaxAttempts; }
    public long getGoogleSheetsRetryDelayMs() { return googleSheetsRetryDelayMs; }

    public String getCsvEncoding() { return csvEncoding; }
    public List<String> getCsvDateFormats() { return csvDateFormats; }
    public String getCsvNumberFormats() { return csvNumberFormats; }

    public int getProcessingBatchSize() { return processingBatchSize; }
    public int getProcessingRetryMaxAttempts() { return processingRetryMaxAttempts; }
    public long getProcessingRetryDelayMs() { return processingRetryDelayMs; }

    public String getLoggingLevel() { return loggingLevel; }
    public String getLoggingFileName() { return loggingFileName; }
    public String getLoggingFileMaxSize() { return loggingFileMaxSize; }
    public int getLoggingFileMaxHistory() { return loggingFileMaxHistory; }

    public boolean isPerformanceMonitoringEnabled() { return performanceMonitoringEnabled; }
    public boolean isPerformanceMetricsEnabled() { return performanceMetricsEnabled; }
    public long getPerformanceReportingInterval() { return performanceReportingInterval; }

    public boolean isDryRunEnabled() { return dryRun; }

    @Override
    public String toString() {
        return "ApplicationConfig{" +
                "googleSheetsApplicationName='" + googleSheetsApplicationName + '\'' +
                ", csvEncoding='" + csvEncoding + '\'' +
                ", processingBatchSize=" + processingBatchSize +
                ", performanceMonitoringEnabled=" + performanceMonitoringEnabled +
                '}';
    }
}