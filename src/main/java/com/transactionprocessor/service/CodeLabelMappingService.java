package com.transactionprocessor.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.transactionprocessor.model.Transaction;

/**
 * Service for managing code to label mappings
 * Maps stock codes to readable labels for Google Sheets
 */
public class CodeLabelMappingService {
    private static final Logger logger = LoggerFactory.getLogger(CodeLabelMappingService.class);
    
    private final Path mappingFilePath;
    private final InputStream mappingInputStream;
    private Map<String, String> codeToLabelMap;
    private boolean loaded = false;

    public CodeLabelMappingService(Path mappingFilePath) {
        this.mappingFilePath = mappingFilePath;
        this.mappingInputStream = null;
        this.codeToLabelMap = new HashMap<>();
    }

    public CodeLabelMappingService(InputStream mappingInputStream) {
        this.mappingFilePath = null;
        this.mappingInputStream = mappingInputStream;
        this.codeToLabelMap = new HashMap<>();
    }

    /**
     * Load code to label mappings from file
     * File format: CODE=LABEL (e.g., SH563220=中证A500ETF（SH563220）)
     */
    public void loadMappings() throws IOException {
        if (mappingInputStream != null) {
            loadMappingsFromInputStream();
        } else if (mappingFilePath != null) {
            loadMappingsFromFile();
        } else {
            logger.warn("No mapping source provided. Using code as label.");
            loaded = true;
        }
    }

    /**
     * Load mappings from file system
     */
    private void loadMappingsFromFile() throws IOException {
        logger.info("Loading code to label mappings from: {}", mappingFilePath);
        
        if (!Files.exists(mappingFilePath)) {
            logger.warn("Mapping file not found at: {}. Using code as label.", mappingFilePath);
            loaded = true;
            return;
        }

        codeToLabelMap.clear();
        final int[] count = {0};

        try (Stream<String> lines = Files.lines(mappingFilePath, StandardCharsets.UTF_8)) {
            lines.forEach(line -> processMappingLine(line, count));
        }
        
        int finalCount = count[0];
        loaded = true;
        logger.info("Loaded {} code to label mappings", finalCount);
    }

    /**
     * Load mappings from input stream (classpath)
     */
    private void loadMappingsFromInputStream() throws IOException {
        logger.info("Loading code to label mappings from input stream");
        
        codeToLabelMap.clear();
        final int[] count = {0};

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(mappingInputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processMappingLine(line, count);
            }
        }
        
        int finalCount = count[0];
        loaded = true;
        logger.info("Loaded {} code to label mappings", finalCount);
    }

    /**
     * Process a single mapping line
     */
    private void processMappingLine(String line, int[] count) {
        String trimmedLine = line.trim();
        if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
            return; // Skip empty lines and comments
        }

        String[] parts = trimmedLine.split("=", 2);
        if (parts.length == 2) {
            String code = parts[0].trim();
            String label = parts[1].trim();
            
            if (!code.isEmpty() && !label.isEmpty()) {
                codeToLabelMap.put(code, label);
                count[0]++;
            } else {
                logger.warn("Invalid mapping line: {}", line);
            }
        } else {
            logger.warn("Invalid mapping line format: {}", line);
        }
    }

    /**
     * Get label for a given code
     * If mapping not found, returns the code itself
     */
    public String getLabel(String code) {
        if (!loaded) {
            try {
                loadMappings();
            } catch (IOException e) {
                logger.error("Failed to load mappings, using code as label", e);
                return code;
            }
        }

        if (code == null || code.trim().isEmpty()) {
            return "";
        }

        String trimmedCode = code.trim();
        String label = codeToLabelMap.get(trimmedCode);
        
        if (label != null) {
            logger.debug("Found mapping for code '{}': '{}'", trimmedCode, label);
            return label;
        } else {
            logger.debug("No mapping found for code '{}', using code as label", trimmedCode);
            return trimmedCode;
        }
    }

    /**
     * Check if a mapping exists for the given code
     */
    public boolean hasMapping(String code) {
        if (!loaded) {
            try {
                loadMappings();
            } catch (IOException e) {
                logger.error("Failed to load mappings", e);
                return false;
            }
        }

        return code != null && codeToLabelMap.containsKey(code.trim());
    }

    /**
     * Add or update a mapping
     */
    public void addMapping(String code, String label) {
        if (code == null || code.trim().isEmpty() || label == null || label.trim().isEmpty()) {
            logger.warn("Invalid code or label for mapping: code='{}', label='{}'", code, label);
            return;
        }

        codeToLabelMap.put(code.trim(), label.trim());
        logger.debug("Added mapping: '{}' -> '{}'", code, label);
    }

    /**
     * Remove a mapping
     */
    public void removeMapping(String code) {
        if (code == null) {
            return;
        }

        String removed = codeToLabelMap.remove(code.trim());
        if (removed != null) {
            logger.debug("Removed mapping for code: '{}'", code);
        }
    }

    /**
     * Get all mappings (unmodifiable map)
     */
    public Map<String, String> getAllMappings() {
        return Collections.unmodifiableMap(codeToLabelMap);
    }

    /**
     * Get the number of loaded mappings
     */
    public int getMappingCount() {
        return codeToLabelMap.size();
    }

    /**
     * Check if mappings have been loaded
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Reload mappings from file
     */
    public void reloadMappings() throws IOException {
        logger.info("Reloading code to label mappings");
        loaded = false;
        loadMappings();
    }

    /**
     * Apply label mapping to a transaction
     */
    public void applyLabelMapping(Transaction transaction) {
        if (transaction == null) {
            return;
        }

        String code = transaction.getCode();
        if (code != null && !code.trim().isEmpty()) {
            String label = getLabel(code);
            transaction.setName(label); // Update the name with the mapped label
            logger.debug("Applied label mapping for transaction: {} -> {}", code, label);
        }
    }

    /**
     * Apply label mapping to multiple transactions
     */
    public void applyLabelMappings(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }

        logger.info("Applying label mappings to {} transactions", transactions.size());
        
        for (Transaction transaction : transactions) {
            applyLabelMapping(transaction);
        }
        
        logger.info("Label mappings applied successfully");
    }

    @Override
    public String toString() {
        return "CodeLabelMappingService{" +
                "mappingFilePath=" + mappingFilePath +
                ", mappingInputStream=" + (mappingInputStream != null ? "provided" : "null") +
                ", loaded=" + loaded +
                ", mappingCount=" + getMappingCount() +
                '}';
    }
}