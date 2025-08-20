package com.sap.fpa61.jenkins;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class JenkinsArtifactDownloader {

    private static final Logger logger = LogManager.getLogger(JenkinsArtifactDownloader.class);

    // Default values
    private static final String DEFAULT_USERNAME = "jenkins-user";
    private static final String DEFAULT_SAVE_DIR = "test-downloads/";

    public static String getSaveDirectory(Properties props) {
        return props.getProperty("SAVE_DIR", DEFAULT_SAVE_DIR);
    }

    public static JSONObject downloadArtifacts(String[] args) throws IOException, JSONException {
        // Load credentials from properties file
        Properties properties = new Properties();
        try (InputStream input = getConfigInputStream()) {
            if (input == null) {
                logger.error("Unable to find config.properties");
                throw new IOException("Config file not found");
            }
            properties.load(input);
        }

        // Get properties with default values if not found
        String jenkinsUsername = properties.getProperty("JENKINS_USERNAME", DEFAULT_USERNAME);
        String jenkinsApiToken = properties.getProperty("JENKINS_API_TOKEN");
        String jenkinsUrl = properties.getProperty("JENKINS_URL");
        String downloadBaseUrl = properties.getProperty("DOWNLOAD_BASE_URL");
        String saveDir = getSaveDirectory(properties);

        String[] requiredFiles = {
            "regression_dolphin.xlsx",
            "regression_dolphin_burn_in.xlsx",
            "burn_in_analysis.xlsx"
        };

        if (jenkinsApiToken == null) {
            logger.error("Missing JENKINS_API_TOKEN in config.properties.");
            throw new IOException("Jenkins API token not found in configuration");
        }

        if (jenkinsUrl == null) {
            logger.error("Missing JENKINS_URL in config.properties.");
            throw new IOException("Jenkins URL not found in configuration");
        }

        if (downloadBaseUrl == null) {
            logger.error("Missing DOWNLOAD_BASE_URL in config.properties.");
            throw new IOException("Download base URL not found in configuration");
        }

        // Fetch JSON from Jenkins API
        JSONObject jsonResponse = fetchJenkinsJson(jenkinsUrl, jenkinsUsername, jenkinsApiToken);
        if (jsonResponse == null) {
            throw new IOException("Failed to retrieve data from Jenkins API");
        }

        // Get the artifacts array
        JSONArray artifacts = jsonResponse.getJSONArray("artifacts");
        if (artifacts.length() == 0) {
            throw new IOException("No artifacts found in Jenkins response");
        }
        logger.info("Successfully retrieved data from Jenkins API");

        // Create download directory if it doesn't exist
        File dir = new File(saveDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create download directory: " + saveDir);
        }
        logger.info("Directory created at: " + saveDir);

        // Download only the required files
        boolean allFilesDownloaded = true;
        for (String requiredFile : requiredFiles) {
            boolean fileFound = false;

            for (int i = 0; i < artifacts.length(); i++) {
                JSONObject artifact = artifacts.getJSONObject(i);
                String fileName = artifact.getString("fileName");

                if (fileName.equals(requiredFile)) {
                    String relativePath = artifact.getString("relativePath");
                    String fileUrl = downloadBaseUrl + relativePath;
                    downloadFile(saveDir, fileUrl, fileName, jenkinsUsername, jenkinsApiToken);
                    fileFound = true;
                    break;
                }
            }

            if (!fileFound) {
                logger.error("Required file not found in artifacts: " + requiredFile);
                allFilesDownloaded = false;
            }
        }

        if (!allFilesDownloaded) {
            throw new IOException("Not all required files were found in artifacts");
        }

        logger.info("All specified test files have been downloaded from the latest Jenkins build.");
        return jsonResponse;
    }

    public static void main(String[] args) {
        try {
            JSONObject result = downloadArtifacts(args);
            if (result == null) {
                logger.error("Download artifacts returned null");
            }
        } catch (IOException | JSONException e) {
            logger.error("Failed to download artifacts: " + e.getMessage(), e);
        }
    }

    public static InputStream getConfigInputStream() {
        return JenkinsArtifactDownloader.class.getClassLoader().getResourceAsStream("config.properties");
    }

    // Creates an http connection and then gets the json from the jenkins api endpoint
    public static JSONObject fetchJenkinsJson(String urlString, String username, String apiToken) throws IOException {
        HttpURLConnection connection = createAuthConnection(urlString, username, apiToken);
        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            String response = readResponse(connection.getInputStream());
            try {
                return new JSONObject(response);
            } catch (JSONException e) {
                logger.error("Failed to parse Jenkins API response as JSON", e);
                throw new IOException("Invalid JSON response from Jenkins API", e);
            }
        } else {
            logger.error("Jenkins API request failed with response code " + responseCode);
            throw new IOException("Jenkins API request failed with HTTP response code: " + responseCode);
        }
    }

    // Downloads a single file to a specified directory after establishing an http connection
    public static void downloadFile(String saveDir, String fileUrl, String fileName, String username, String apiToken) throws IOException {
        logger.info("Downloading: " + fileName + "...");
        HttpURLConnection connection = createAuthConnection(fileUrl, username, apiToken);

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(saveDir + fileName)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            logger.info("Saved: " + fileName);
        } else {
            logger.error("Failed to download " + fileName + " (HTTP " + responseCode + ")");
            throw new IOException("Failed to download " + fileName + " (HTTP " + responseCode + ")");
        }
    }

    // Returns an http connection with authorization property
    static HttpURLConnection createAuthConnection(String urlString, String username, String apiToken) throws IOException {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            String auth = username + ":" + apiToken;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
            return connection;
        } catch (IOException e) {
            logger.error("Failed to create HTTP connection to: " + urlString, e);
            throw e;
        }
    }

    // Reads the response from the http connection to a string
    static String readResponse(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } catch (IOException e) {
            logger.error("Failed to read response from input stream", e);
            throw e;
        }
    }
}
