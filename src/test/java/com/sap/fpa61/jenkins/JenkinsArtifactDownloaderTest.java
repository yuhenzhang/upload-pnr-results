package com.sap.fpa61.jenkins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class JenkinsArtifactDownloaderTest {

    @TempDir
    Path tempDir;

    // FIll IN values from your config.properties, or other values you want to test
    private final String USERNAME = "";
    private final String API_TOKEN = "";
    private final String JENKINS_URL = "";
    private final String DOWNLOAD_BASE_URL = "";
    private final String[] TEST_FILES = {
        "regression_dolphin.xlsx",
        "regression_dolphin_burn_in.xlsx",
        "burn_in_analysis.xlsx"
    };

    private Properties mockProperties;
    private HttpURLConnection mockConnection;
    private JSONObject mockJsonResponse;

    // Test appender to capture log messages
    private TestAppender testAppender;
    private Logger rootLogger;

    // Security manager to prevent System.exit calls from terminating tests
    private static class NoExitSecurityManager extends SecurityManager {

        @Override
        public void checkPermission(Permission perm) {
            // Allow anything
        }

        @Override
        public void checkPermission(Permission perm, Object context) {
            // Allow anything
        }

        @Override
        public void checkExit(int status) {
            throw new ExitException(status);
        }
    }

    // Custom exception for System.exit calls
    private static class ExitException extends SecurityException {

        private final int status;

        public ExitException(int status) {
            super("System.exit(" + status + ") was called");
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    private SecurityManager originalSecurityManager;

    @BeforeEach
    public void setUp() throws Exception {
        // Install security manager to catch System.exit
        originalSecurityManager = System.getSecurityManager();
        System.setSecurityManager(new NoExitSecurityManager());

        // Mock properties
        mockProperties = new Properties();
        mockProperties.setProperty("JENKINS_USERNAME", USERNAME);
        mockProperties.setProperty("JENKINS_API_TOKEN", API_TOKEN);
        mockProperties.setProperty("JENKINS_URL", JENKINS_URL);
        mockProperties.setProperty("DOWNLOAD_BASE_URL", DOWNLOAD_BASE_URL);
        mockProperties.setProperty("SAVE_DIR", tempDir.toString() + "/");

        // Mock HTTP connection
        mockConnection = mock(HttpURLConnection.class);

        // Mock JSON response
        mockJsonResponse = createMockJenkinsJsonResponse();

        // Setup test appender to capture log messages
        rootLogger = (Logger) LogManager.getRootLogger();
        testAppender = new TestAppender();
        testAppender.start();
        rootLogger.addAppender(testAppender);
    }

    @AfterEach
    public void tearDown() {
        // Remove test appender
        rootLogger.removeAppender(testAppender);
        testAppender.stop();

        // Restore original security manager
        System.setSecurityManager(originalSecurityManager);
    }

    @Test
    public void testMainMethodWithValidConfig() throws Exception {
        // Clear any existing log messages
        testAppender.clear();

        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Call the real main method but bypass System.exit()
            mockedStatic.when(() -> JenkinsArtifactDownloader.downloadArtifacts(any())).thenReturn(mockJsonResponse);
            mockedStatic.when(() -> JenkinsArtifactDownloader.main(any())).thenCallRealMethod();

            // Call the main method
            String[] args = {};
            JenkinsArtifactDownloader.main(args);

            // Verify method was called correctly
            mockedStatic.verify(() -> JenkinsArtifactDownloader.downloadArtifacts(any()), times(1));
        }
    }

    @Test
    public void testFetchJenkinsJson() throws Exception {
        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Make the actual method available for the test
            mockedStatic.when(() -> JenkinsArtifactDownloader.fetchJenkinsJson(
                    anyString(), anyString(), anyString())).thenCallRealMethod();

            // Mock the createAuthConnection method
            mockedStatic.when(() -> JenkinsArtifactDownloader.createAuthConnection(
                    eq(JENKINS_URL), eq(USERNAME), eq(API_TOKEN)))
                    .thenReturn(mockConnection);

            // Mock the connection response
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);

            // Mock the readResponse method
            String jsonResponse = "{\"artifacts\":[{\"fileName\":\"test.xlsx\",\"relativePath\":\"path/test.xlsx\"}]}";
            InputStream mockInputStream = new ByteArrayInputStream(jsonResponse.getBytes(StandardCharsets.UTF_8));
            when(mockConnection.getInputStream()).thenReturn(mockInputStream);

            mockedStatic.when(() -> JenkinsArtifactDownloader.readResponse(any(InputStream.class)))
                    .thenReturn(jsonResponse);

            // Call the method under test
            JSONObject result = JenkinsArtifactDownloader.fetchJenkinsJson(JENKINS_URL, USERNAME, API_TOKEN);

            // Verify the result
            assertNotNull(result);
            assertEquals("test.xlsx", result.getJSONArray("artifacts").getJSONObject(0).getString("fileName"));
        }
    }

    @Test
    public void testFetchJenkinsJsonWithError() throws Exception {
        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Make the actual method available for the test
            mockedStatic.when(() -> JenkinsArtifactDownloader.fetchJenkinsJson(
                    anyString(), anyString(), anyString())).thenCallRealMethod();

            // Mock the createAuthConnection method
            mockedStatic.when(() -> JenkinsArtifactDownloader.createAuthConnection(
                    eq(JENKINS_URL), eq(USERNAME), eq(API_TOKEN)))
                    .thenReturn(mockConnection);

            // Mock the connection response with an error
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_UNAUTHORIZED);

            // Verify the method throws an exception
            assertThrows(IOException.class, () -> {
                JenkinsArtifactDownloader.fetchJenkinsJson(JENKINS_URL, USERNAME, API_TOKEN);
            });
        }
    }

    @Test
    public void testDownloadFile() throws Exception {
        String saveDir = tempDir.toString() + "/";
        String fileName = "test.xlsx";
        String fileUrl = DOWNLOAD_BASE_URL + "path/" + fileName;

        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Make the actual method available for the test
            mockedStatic.when(() -> JenkinsArtifactDownloader.downloadFile(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenCallRealMethod();

            // Mock the createAuthConnection method
            mockedStatic.when(() -> JenkinsArtifactDownloader.createAuthConnection(
                    eq(fileUrl), eq(USERNAME), eq(API_TOKEN)))
                    .thenReturn(mockConnection);

            // Mock the connection response
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);

            // Create test content for the file
            byte[] fileContent = "Test file content".getBytes(StandardCharsets.UTF_8);
            InputStream mockInputStream = new ByteArrayInputStream(fileContent);
            when(mockConnection.getInputStream()).thenReturn(mockInputStream);

            // Call the method under test
            JenkinsArtifactDownloader.downloadFile(saveDir, fileUrl, fileName, USERNAME, API_TOKEN);

            // Verify the file was created correctly
            File downloadedFile = new File(saveDir + fileName);
            assertTrue(downloadedFile.exists());

            // Verify the file content
            byte[] actualContent = Files.readAllBytes(downloadedFile.toPath());
            assertArrayEquals(fileContent, actualContent);
        }
    }

    @Test
    public void testDownloadFileWithError() throws Exception {
        String saveDir = tempDir.toString() + "/";
        String fileName = "test.xlsx";
        String fileUrl = DOWNLOAD_BASE_URL + "path/" + fileName;

        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Make the actual method available for the test
            mockedStatic.when(() -> JenkinsArtifactDownloader.downloadFile(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenCallRealMethod();

            // Mock the createAuthConnection method
            mockedStatic.when(() -> JenkinsArtifactDownloader.createAuthConnection(
                    eq(fileUrl), eq(USERNAME), eq(API_TOKEN)))
                    .thenReturn(mockConnection);

            // Mock the connection response with an error
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);

            // Verify the method throws an exception
            assertThrows(IOException.class, () -> {
                JenkinsArtifactDownloader.downloadFile(saveDir, fileUrl, fileName, USERNAME, API_TOKEN);
            });
        }
    }

    @Test
    public void testCreateAuthConnection() throws Exception {
        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Make the actual method available for the test
            mockedStatic.when(() -> JenkinsArtifactDownloader.createAuthConnection(
                    anyString(), anyString(), anyString())).thenCallRealMethod();

            // Mock URL and URLConnection creation
            HttpURLConnection connection = mock(HttpURLConnection.class);
            mockedStatic.when(() -> JenkinsArtifactDownloader.createAuthConnection(
                    eq(JENKINS_URL), eq(USERNAME), eq(API_TOKEN))).thenReturn(connection);

            // Call the method and verify
            HttpURLConnection result = JenkinsArtifactDownloader.createAuthConnection(JENKINS_URL, USERNAME, API_TOKEN);
            assertNotNull(result);
            assertSame(connection, result);
        }
    }

    @Test
    public void testReadResponse() throws Exception {
        String expectedResponse = "{\"test\":\"value\"}";
        InputStream inputStream = new ByteArrayInputStream(expectedResponse.getBytes(StandardCharsets.UTF_8));

        String result = JenkinsArtifactDownloader.readResponse(inputStream);

        assertEquals(expectedResponse, result);
    }

    @Test
    public void testDownloadArtifactsWithMissingConfigFile() throws Exception {
        // Clear any existing log messages
        testAppender.clear();

        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Let downloadArtifacts be called for real
            mockedStatic.when(() -> JenkinsArtifactDownloader.downloadArtifacts(any())).thenCallRealMethod();

            // Mock the config input stream to return null
            mockedStatic.when(JenkinsArtifactDownloader::getConfigInputStream).thenReturn(null);

            assertThrows(IOException.class, () -> {
                JenkinsArtifactDownloader.downloadArtifacts(new String[]{});
            });

            // Verify the error message was logged
            assertTrue(testAppender.containsMessage("Unable to find config.properties"),
                    "Log should contain the error message about missing config.properties");
        }
    }

    @Test
    public void testDownloadArtifactsWithMissingCredentials() throws Exception {
        // Clear any existing log messages
        testAppender.clear();

        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Let downloadArtifacts be called for real
            mockedStatic.when(() -> JenkinsArtifactDownloader.downloadArtifacts(any())).thenCallRealMethod();

            // Create incomplete properties without credentials
            Properties incompleteProps = new Properties();
            incompleteProps.setProperty("JENKINS_URL", JENKINS_URL);
            incompleteProps.setProperty("DOWNLOAD_BASE_URL", DOWNLOAD_BASE_URL);
            incompleteProps.setProperty("SAVE_DIR", tempDir.toString() + "/");

            // Set up mock input stream with incomplete properties
            ByteArrayOutputStream propsOutput = new ByteArrayOutputStream();
            incompleteProps.store(propsOutput, null);
            InputStream mockInputStream = new ByteArrayInputStream(propsOutput.toByteArray());

            // Mock the config input stream
            mockedStatic.when(JenkinsArtifactDownloader::getConfigInputStream).thenReturn(mockInputStream);

            assertThrows(IOException.class, () -> {
                JenkinsArtifactDownloader.downloadArtifacts(new String[]{});
            });

            // Verify the error message was logged
            assertTrue(testAppender.containsMessage("Missing JENKINS_API_TOKEN"),
                    "Log should contain the error message about missing API token");
        }
    }

    @Test
    public void testDownloadArtifactsWithFailedJenkinsApiCall() throws Exception {
        // Clear any existing log messages
        testAppender.clear();

        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Allow downloadArtifacts to be called for real
            mockedStatic.when(() -> JenkinsArtifactDownloader.downloadArtifacts(any())).thenCallRealMethod();

            // Mock the config input stream
            InputStream mockInputStream = createMockPropertiesInputStream();
            mockedStatic.when(JenkinsArtifactDownloader::getConfigInputStream).thenReturn(mockInputStream);

            // Mock fetchJenkinsJson to throw exception (simulating failure)
            mockedStatic.when(() -> JenkinsArtifactDownloader.fetchJenkinsJson(
                    eq(JENKINS_URL), eq(USERNAME), eq(API_TOKEN)))
                    .thenThrow(new IOException("Failed to retrieve data from Jenkins API"));

            assertThrows(IOException.class, () -> {
                JenkinsArtifactDownloader.downloadArtifacts(new String[]{});
            });
        }
    }

    @Test
    public void testSuccessfulDownloadArtifacts() throws Exception {
        try (MockedStatic<JenkinsArtifactDownloader> mockedStatic = Mockito.mockStatic(JenkinsArtifactDownloader.class)) {
            // Allow downloadArtifacts to be called for real
            mockedStatic.when(() -> JenkinsArtifactDownloader.downloadArtifacts(any())).thenCallRealMethod();

            // Make getSaveDirectory return a valid directory path
            mockedStatic.when(() -> JenkinsArtifactDownloader.getSaveDirectory(any()))
                    .thenReturn(tempDir.toString() + "/");

            // Mock the config input stream
            InputStream mockInputStream = createMockPropertiesInputStream();
            mockedStatic.when(JenkinsArtifactDownloader::getConfigInputStream).thenReturn(mockInputStream);

            // Mock fetchJenkinsJson to return valid response
            mockedStatic.when(() -> JenkinsArtifactDownloader.fetchJenkinsJson(
                    eq(JENKINS_URL), eq(USERNAME), eq(API_TOKEN)))
                    .thenReturn(mockJsonResponse);

            // Correctly mock static void method
            mockedStatic.when(() -> JenkinsArtifactDownloader.downloadFile(
                    anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> null);

            // Call the method
            JSONObject result = JenkinsArtifactDownloader.downloadArtifacts(new String[]{});

            // Verify result
            assertNotNull(result);
            assertEquals(mockJsonResponse, result);

            // Verify each required file was downloaded
            for (String fileName : TEST_FILES) {
                mockedStatic.verify(() -> JenkinsArtifactDownloader.downloadFile(
                        anyString(), contains(fileName), eq(fileName), anyString(), anyString()), times(1));
            }
        }
    }

    // Helper methods
    private InputStream createMockPropertiesInputStream() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        mockProperties.store(output, null);
        return new ByteArrayInputStream(output.toByteArray());
    }

    private JSONObject createMockJenkinsJsonResponse() {
        JSONObject jsonObject = new JSONObject();
        JSONArray artifacts = new JSONArray();

        for (String fileName : TEST_FILES) {
            JSONObject artifact = new JSONObject();
            artifact.put("fileName", fileName);
            artifact.put("relativePath", "path/" + fileName);
            artifacts.put(artifact);
        }

        // Add some non-matching files too
        JSONObject extraArtifact = new JSONObject();
        extraArtifact.put("fileName", "extra.xlsx");
        extraArtifact.put("relativePath", "path/extra.xlsx");
        artifacts.put(extraArtifact);

        jsonObject.put("artifacts", artifacts);
        return jsonObject;
    }

    // Custom Log4j Appender to capture log messages during tests
    private class TestAppender extends AbstractAppender {

        private StringBuilder logMessages = new StringBuilder();

        public TestAppender() {
            super("TestAppender", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            logMessages.append(event.getMessage().getFormattedMessage()).append("\n");
        }

        public void clear() {
            logMessages = new StringBuilder();
        }

        public boolean containsMessage(String searchString) {
            return logMessages.toString().contains(searchString);
        }
    }
}
