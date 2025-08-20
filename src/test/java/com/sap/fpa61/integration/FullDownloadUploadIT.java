package com.sap.fpa61.integration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sap.fpa61.db.HanaDataUploader;

public class FullDownloadUploadIT {

    private static final Logger logger = LogManager.getLogger(FullDownloadUploadIT.class);
    private static final String TEST_CONFIG_FILE = "test-config.properties";
    private static final String TEST_SAVE_DIR = "test-result-downloads/";
    private static final String PROJECT_DIR = "src/test/resources/test-data";
    private static Connection connection;
    private static final Properties testProps = new Properties();
    private static final String[] requiredFiles = {
        "regression_dolphin.xlsx",
        "regression_dolphin_burn_in.xlsx",
        "burn_in_analysis.xlsx"
    };

    @BeforeAll
    public static void setUp() throws Exception {
        // Create test properties file with test values
        testProps.setProperty("DB_HOST", "localhost");
        testProps.setProperty("DB_PORT", "30041");
        testProps.setProperty("DB_USER", "SYSTEM");
        testProps.setProperty("DB_PASSWORD", "Manager1");
        testProps.setProperty("DB_ENCRYPT", "true");
        testProps.setProperty("DB_VALIDATE_CERTIFICATE", "false");

        try (FileOutputStream out = new FileOutputStream(TEST_CONFIG_FILE)) {
            testProps.store(out, "Test configuration properties");
        } catch (IOException e) {
            throw new IOException("Failed to create test properties file: " + TEST_CONFIG_FILE, e);
        }

        // Create test directory if it doesn't exist
        File testDir = new File(TEST_SAVE_DIR);
        if (!testDir.exists() && !testDir.mkdirs()) {
            throw new IOException("Failed to create test directory: " + TEST_SAVE_DIR);
        }

        // Copy test files from resources to test directory
        copyTestFilesFromResources();
    }

    private static void copyTestFilesFromResources() throws IOException {
        // Get the project root directory
        File projectDir = new File(System.getProperty("user.dir"));
        File resourcesDir = new File(projectDir, PROJECT_DIR);

        if (!resourcesDir.exists()) {
            throw new IOException("Test resources directory not found: " + resourcesDir.getAbsolutePath());
        }

        for (String fileName : requiredFiles) {
            File sourceFile = new File(resourcesDir, fileName);
            if (!sourceFile.exists()) {
                throw new IOException("Test file not found: " + sourceFile.getAbsolutePath());
            }

            Path targetPath = Paths.get(TEST_SAVE_DIR + fileName);
            Files.copy(sourceFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Copied test file: {} to {}", fileName, targetPath);
        }
    }

    @AfterAll
    public static void tearDown() throws Exception {
        // Delete test files
        logger.info("Deleting test files...");
        cleanupTestFiles();

        // Delete test properties file
        File testPropsFile = new File(TEST_CONFIG_FILE);
        if (testPropsFile.exists() && !testPropsFile.delete()) {
            throw new IOException("Failed to delete test properties file: " + TEST_CONFIG_FILE);
        }

        // Drop the schema if connected (for cleanup)
        logger.info("Dropping schema and closing connection...");
        if (connection != null && !connection.isClosed()) {
            dropSchema();
            connection.close();
        }
    }

    private static void cleanupTestFiles() throws IOException {
        File testDir = new File(TEST_SAVE_DIR);
        if (testDir.exists()) {
            for (File file : testDir.listFiles()) {
                if (!file.delete()) {
                    throw new IOException("Failed to delete test file: " + file.getName());
                }
            }
            if (!testDir.delete()) {
                throw new IOException("Failed to delete test directory: " + testDir.getName());
            }
        }
    }

    @Test
    void testCompleteWorkflow() throws Exception {
        // Create a mock Jenkins response JSON with required fields
        JSONObject mockJenkinsResponse = createMockJenkinsResponse();

        // Verify files were copied correctly
        verifyTestFiles();

        // Upload data to HANA
        HanaDataUploader uploader = new HanaDataUploader(mockJenkinsResponse);
        uploader.uploadAllFiles(TEST_SAVE_DIR);

        // Connect to verify data
        connectToTestDatabase();

        // Verify data was uploaded
        verifyDataUpload();
    }

    private JSONObject createMockJenkinsResponse() throws Exception {
        JSONObject jsonResponse = new JSONObject();
        jsonResponse.put("fullDisplayName", "TestJobs » pnr_test_automation #123");
        jsonResponse.put("id", "123");
        return jsonResponse;
    }

    private void verifyTestFiles() {
        logger.info("Verifying test files...");
        for (String fileName : requiredFiles) {
            File file = new File(TEST_SAVE_DIR + fileName);
            assertTrue(file.exists(), "File should exist: " + fileName);
            assertTrue(file.length() > 0, "File should not be empty: " + fileName);
        }
    }

    private void connectToTestDatabase() throws SQLException {
        String dbHost = testProps.getProperty("DB_HOST");
        String dbPort = testProps.getProperty("DB_PORT");
        String dbUser = testProps.getProperty("DB_USER");
        String dbPassword = testProps.getProperty("DB_PASSWORD");
        String dbEncrypt = testProps.getProperty("DB_ENCRYPT");
        String dbValidateCert = testProps.getProperty("DB_VALIDATE_CERTIFICATE");

        String url = "jdbc:sap://" + dbHost + ":" + dbPort + "/";
        logger.info("Connecting to test database at " + url + " for verification");

        Properties connProps = new Properties();
        connProps.setProperty("user", dbUser);
        connProps.setProperty("password", dbPassword);
        connProps.setProperty("encrypt", dbEncrypt);
        connProps.setProperty("validateCertificate", dbValidateCert);

        connection = DriverManager.getConnection(url, connProps);
    }

    private static void dropSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP SCHEMA REGRESSION_UPLOAD CASCADE");
        } catch (SQLException e) {
            throw new SQLException("Failed to drop schema", e);
        }
    }

    private void verifyDataUpload() throws SQLException {
        // Verify TEST_SCENARIO table has entries
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM REGRESSION_UPLOAD.TEST_SCENARIO")) {
            rs.next();
            int scenarioCount = rs.getInt(1);
            assertTrue(scenarioCount > 0, "Should have at least one test scenario");
            logger.info("At least one test scenario found");
        } catch (SQLException e) {
            throw new SQLException("Failed to retrieve scenario count", e);
        }

        // Verify TEST_RUN table has entries
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM REGRESSION_UPLOAD.TEST_RUN")) {
            rs.next();
            int runCount = rs.getInt(1);
            assertTrue(runCount > 0, "Should have at least one test run");
            logger.info("At least one test run found");
        } catch (SQLException e) {
            throw new SQLException("Failed to retrieve run count", e);
        }

        // Verify TEST_RESULT table has entries
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM REGRESSION_UPLOAD.TEST_RESULT")) {
            rs.next();
            int resultCount = rs.getInt(1);
            assertTrue(resultCount > 0, "Should have test results");
            logger.info("Test results found");
        } catch (SQLException e) {
            throw new SQLException("Failed to retrieve result count", e);
        }

        // Verify burn-in view has entries
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM REGRESSION_UPLOAD.BURN_IN_RESULTS")) {
            rs.next();
            int burnInCount = rs.getInt(1);
            assertTrue(burnInCount > 0, "Should have burn-in view");
            logger.info("Burn-in view found");
        } catch (SQLException e) {
            throw new SQLException("Failed to retrieve burn-in view", e);
        }

        // Verify regression view has entries
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM REGRESSION_UPLOAD.REGRESSION_RESULTS")) {
            rs.next();
            int regressionCount = rs.getInt(1);
            assertTrue(regressionCount > 0, "Should have regression view");
            logger.info("Regression view found");
        } catch (SQLException e) {
            throw new SQLException("Failed to retrieve regression view", e);
        }

        // Verify Jenkins job data was properly stored
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT * FROM REGRESSION_UPLOAD.TEST_RUN WHERE JENKINS_JOB_NAME = ?")) {
            pstmt.setString(1, "TestJobs » pnr_test_automation");
            ResultSet rs = pstmt.executeQuery();
            assertTrue(rs.next(), "Should have matching Jenkins job data");
            logger.info("Jenkins job name matches");
        } catch (SQLException e) {
            throw new SQLException("Failed to retrieve Jenkins job name", e);
        }
    }
}
