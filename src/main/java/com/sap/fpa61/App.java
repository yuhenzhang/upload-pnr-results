package com.sap.fpa61;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import com.sap.fpa61.db.HanaDataUploader;
import com.sap.fpa61.jenkins.JenkinsArtifactDownloader;

public class App {

    private static final Logger logger = LogManager.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        logger.info("Starting application");

        // Load configuration properties
        Properties props;
        try {
            props = loadProperties();
        } catch (IOException e) {
            logger.error("Failed to load configuration: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }

        // Execute download process
        JSONObject jsonResponse;
        String saveDir;
        try {
            jsonResponse = JenkinsArtifactDownloader.downloadArtifacts(args);
            if (jsonResponse == null) {
                throw new RuntimeException("Download failed: jsonResponse is null");
            }
            saveDir = JenkinsArtifactDownloader.getSaveDirectory(props);
            logger.info("Download completed successfully");
        } catch (JSONException e) {
            logger.error("Artifact download failed: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }

        // Execute upload process
        try {
            HanaDataUploader uploader = new HanaDataUploader(jsonResponse);
            uploader.uploadAllFiles(saveDir);
            logger.info("Upload to HANA database completed successfully");
        } catch (JSONException e) {
            logger.error("Database upload failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // Helper method that loads the configuration properties
    private static Properties loadProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream input = JenkinsArtifactDownloader.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (input == null) {
                logger.warn("config.properties not found, using defaults");
            } else {
                props.load(input);
                logger.debug("Loaded configuration properties");
            }
        }
        return props;
    }
}
