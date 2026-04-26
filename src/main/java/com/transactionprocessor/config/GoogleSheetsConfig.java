package com.transactionprocessor.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

/**
 * Google Sheets API configuration and service creation
 */
public class GoogleSheetsConfig {
    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsConfig.class);
    
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(SheetsScopes.SPREADSHEETS);
    
    private final ApplicationConfig config;
    private Sheets sheetsService;

    public GoogleSheetsConfig(ApplicationConfig config) {
        this.config = config;
    }

    /**
     * Create and return the Google Sheets service instance
     */
    public Sheets getSheetsService() throws IOException, GeneralSecurityException {
        if (sheetsService == null) {
            logger.info("Creating Google Sheets service");
            sheetsService = createSheetsService();
            logger.info("Google Sheets service created successfully");
        }
        return sheetsService;
    }

    /**
     * Create a new Google Sheets service instance
     */
    private Sheets createSheetsService() throws IOException, GeneralSecurityException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        
        // Load credentials
        GoogleCredentials credentials = loadCredentials();
        
        // Create the sheets service
        return new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, new HttpCredentialsAdapter(credentials))
                .setApplicationName(config.getGoogleSheetsApplicationName())
                .build();
    }

    /**
     * Load Google credentials from service account file
     */
    private GoogleCredentials loadCredentials() throws IOException {
        String credentialsPath = config.getGoogleSheetsCredentialsPath();
        logger.debug("Loading Google credentials from: {}", credentialsPath);
        
        // Try to load from classpath first
        try (InputStream serviceAccountStream = getClass().getClassLoader().getResourceAsStream(credentialsPath)) {
            if (serviceAccountStream != null) {
                logger.info("Loaded credentials from classpath: {}", credentialsPath);
                GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccountStream)
                        .createScoped(SCOPES);
                logger.debug("Google credentials loaded successfully from classpath");
                return credentials;
            }
        }
        
        // Fallback to file system if not found in classpath
        logger.info("Credentials not found in classpath, trying file system: {}", credentialsPath);
        try (FileInputStream serviceAccountStream = new FileInputStream(credentialsPath)) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccountStream)
                    .createScoped(SCOPES);
            logger.debug("Google credentials loaded successfully from file system");
            return credentials;
        }
    }

    /**
     * Test the connection to Google Sheets API
     */
    public boolean testConnection() {
        try {
            Sheets service = getSheetsService();
            // Try to get the spreadsheet metadata to test connection
            // This is a lightweight operation to verify credentials
            logger.info("Testing Google Sheets API connection");
            return true;
        } catch (Exception e) {
            logger.error("Failed to connect to Google Sheets API", e);
            return false;
        }
    }

    /**
     * Get the application name for Google Sheets API
     */
    public String getApplicationName() {
        return config.getGoogleSheetsApplicationName();
    }

    /**
     * Get the API version
     */
    public String getApiVersion() {
        return config.getGoogleSheetsApiVersion();
    }

    /**
     * Get max retry attempts for API calls
     */
    public int getMaxRetryAttempts() {
        return config.getGoogleSheetsRetryMaxAttempts();
    }

    /**
     * Get retry delay in milliseconds
     */
    public long getRetryDelayMs() {
        return config.getGoogleSheetsRetryDelayMs();
    }
}