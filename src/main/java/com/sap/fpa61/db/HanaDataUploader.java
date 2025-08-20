package com.sap.fpa61.db;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONException;
import org.json.JSONObject;

public class HanaDataUploader {

    static final Logger logger = LogManager.getLogger(HanaDataUploader.class);

    static final String BURN_IN_FILE = "burn_in_analysis.xlsx";
    static final String REGRESSION_FILE = "regression_dolphin.xlsx";
    static final String REGRESSION_BURN_IN_FILE = "regression_dolphin_burn_in.xlsx";

    static final String TABLE_TEST_RUN = "TEST_RUN";
    static final String TABLE_TEST_SCENARIO = "TEST_SCENARIO";
    static final String TABLE_TEST_RESULT = "TEST_RESULT";

    static final int BATCH_SIZE = 100;
    static final int MAX_DEPLOYMENT_LENGTH = 255;
    static final int MAX_IMAGE_LENGTH = 255;
    static final int MAX_BUILD_NUMBER_LENGTH = 255;
    static final int MAX_JOB_NAME_LENGTH = 255;

    Connection connection;
    final String jenkinsJobName;
    final String buildNumber;

    public HanaDataUploader(JSONObject jsonResponse) throws JSONException {
        if (!jsonResponse.has("fullDisplayName") || jsonResponse.isNull("fullDisplayName")) {
            throw new JSONException("Missing required field 'fullDisplayName' in Jenkins API response");
        }
        if (!jsonResponse.has("id") || jsonResponse.isNull("id")) {
            throw new JSONException("Missing required field 'id' in Jenkins API response");
        }

        this.jenkinsJobName = jsonResponse.getString("fullDisplayName");
        this.buildNumber = jsonResponse.getString("id");
    }

    // Main upload controller method
    public void uploadAllFiles(String saveDir) throws Exception {
        try {
            connectToHana();
            if (connection == null) {
                throw new SQLException("Failed to establish database connection");
            }
            logger.info("HANA DB connection established");
        } catch (SQLException | IOException e) {
            logger.error("Database connection failed: " + e.getMessage(), e);
            throw new Exception("Failed to connect to database", e);
        }

        boolean burnInUploaded = false;
        boolean regressionUploaded = false;
        boolean regressionBurnInUploaded = false;

        // Process burn-in analysis
        try {
            logger.info("Burn-in results uploading...");
            processBurnInAnalysis(saveDir + BURN_IN_FILE);
            logger.info("Burn-in results uploaded successfully");
            burnInUploaded = true;
        } catch (Exception e) {
            logger.error("Error uploading burn-in results: " + e.getMessage(), e);
        }

        // Process regression files
        try {
            logger.info("Regression results uploading...");
            processRegressionFile(saveDir + REGRESSION_FILE);
            logger.info("Regression results uploaded successfully");
            regressionUploaded = true;
        } catch (Exception e) {
            logger.error("Error uploading regression results: " + e.getMessage(), e);
        }

        // Process regression burn-in files
        try {
            logger.info("Regression burn-in results uploading...");
            processRegressionFile(saveDir + REGRESSION_BURN_IN_FILE);
            logger.info("Regression burn-in results uploaded successfully");
            regressionBurnInUploaded = true;
        } catch (Exception e) {
            logger.error("Error uploading regression burn-in results: " + e.getMessage(), e);
        }

        // Close connection
        try {
            closeConnection();
        } catch (Exception e) {
            logger.error("Error closing database connection: " + e.getMessage(), e);
        }

        // Throw exception if all uploads failed
        if (!burnInUploaded && !regressionUploaded && !regressionBurnInUploaded) {
            throw new Exception("All file uploads failed");
        }
    }

    // Establishes connection to HANA db server
    void connectToHana() throws SQLException, IOException {
        try {
            // Load properties from config.properties file
            Properties configProps = new Properties();
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties")) {
                if (input == null) {
                    logger.error("Unable to find config.properties");
                    throw new SQLException("Configuration file not found");
                }
                configProps.load(input);
            } catch (IOException e) {
                logger.error("Error loading configuration: " + e.getMessage(), e);
                throw new IOException("Failed to load configuration", e);
            }

            String hanaHost = configProps.getProperty("DB_HOST");
            String hanaPort = configProps.getProperty("DB_PORT");
            String dbUser = configProps.getProperty("DB_USER");
            String dbPassword = configProps.getProperty("DB_PASSWORD");
            String dbEncrypt = configProps.getProperty("DB_ENCRYPT");
            String dbValidateCert = configProps.getProperty("DB_VALIDATE_CERTIFICATE");

            String url = "jdbc:sap://" + hanaHost + ":" + hanaPort + "/";
            logger.info("Connecting to HANA DB at " + url + "...");

            Properties connProps = new Properties();
            connProps.setProperty("user", dbUser);
            connProps.setProperty("password", dbPassword);
            connProps.setProperty("encrypt", dbEncrypt);
            connProps.setProperty("validateCertificate", dbValidateCert);

            connection = DriverManager.getConnection(url, connProps);
            executeDDLScript();

            // Verify connection to the schema
            try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT CURRENT_SCHEMA FROM DUMMY")) {
                if (rs.next()) {
                    logger.info("Connected to schema: " + rs.getString(1));
                }
            }
        } catch (SQLException e) {
            logger.error("Connection to HANA failed: " + e.getMessage(), e);
            throw new SQLException("Connection to HANA failed", e);
        }
    }

    void executeDDLScript() throws IOException, SQLException {
        String scriptPath = "src/main/resources/ddl_script.sql";
        logger.info("Executing DDL script...");

        try {
            // Read the SQL file
            Path path = Paths.get(scriptPath);
            String sqlScript = Files.readString(path);

            // Split the script by semicolons to execute each statement separately
            // But preserve semicolons inside quotes
            List<String> statements = splitSqlStatements(sqlScript);

            try (Statement stmt = connection.createStatement()) {
                for (String statement : statements) {
                    // Skip empty statements
                    statement = statement.trim();
                    if (!statement.isEmpty()) {
                        try {
                            stmt.execute(statement);
                            logger.debug("Executed SQL: " + statement.substring(0, Math.min(50, statement.length()))
                                    + (statement.length() > 50 ? "..." : ""));
                        } catch (SQLException e) {
                            logger.error("Error executing SQL statement: " + statement, e);
                            throw new SQLException("Failed to execute SQL statement", e);
                        }
                    }
                }
                logger.info("DDL script execution completed");
            }
        } catch (IOException e) {
            logger.error("Failed to read DDL script file: " + e.getMessage(), e);
            throw new IOException("Failed to read DDL script file", e);
        } catch (SQLException e) {
            logger.error("Failed to execute DDL script: " + e.getMessage(), e);
            throw new SQLException("Failed to execute DDL script", e);
        }
    }

    // Helper method to help split SQL script into separate statements while handling quoted content
    List<String> splitSqlStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = '"';

        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);

            // Handle quotes (both single and double)
            if ((c == '"' || c == '\'') && (i == 0 || script.charAt(i - 1) != '\\')) {
                if (!inQuote) {
                    inQuote = true;
                    quoteChar = c;
                } else if (c == quoteChar) {
                    inQuote = false;
                }
            }

            // Handle semicolons (statement separators) when not in quotes
            if (c == ';' && !inQuote) {
                currentStatement.append(c);
                statements.add(currentStatement.toString());
                currentStatement = new StringBuilder();
            } else {
                currentStatement.append(c);
            }
        }

        // Add the last statement if there's anything left
        if (currentStatement.length() > 0) {
            statements.add(currentStatement.toString());
        }

        return statements;
    }

    // Processes burn-in analysis Excel file (special structure)
    void processBurnInAnalysis(String filePath) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new File(filePath))) {
            Sheet sheet = workbook.getSheet("results");
            if (sheet == null) {
                logger.warn("Sheet 'results' not found in file: " + filePath);
                return;
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                logger.warn("Header row not found in sheet 'results'");
                return;
            }

            // Process each column as separate test run (columns B, C, D...)
            for (int col = 1; col <= headerRow.getLastCellNum(); col++) {
                Cell dateCell = headerRow.getCell(col);
                if (dateCell == null) {
                    logger.debug("Skipping empty column " + col);
                    continue;
                }

                String jobDate;
                if (dateCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(dateCell)) {
                    // Handle date formatted cells
                    java.util.Date date = dateCell.getDateCellValue();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                    jobDate = sdf.format(date);
                } else {
                    // Handle string dates
                    jobDate = dateCell.getStringCellValue();
                }

                Row deploymentRow = sheet.getRow(1);
                Row imageRow = sheet.getRow(2);

                if (deploymentRow == null || imageRow == null) {
                    logger.warn("Missing deployment or image row");
                    continue;
                }

                Cell deploymentCell = deploymentRow.getCell(col);
                Cell imageCell = imageRow.getCell(col);

                if (deploymentCell == null || imageCell == null) {
                    logger.warn("Missing deployment or image data for column " + col);
                    continue;
                }

                // Process deployment string to remove "Deployment: " prefix
                String deploymentFull = deploymentCell.getStringCellValue();
                String[] deploymentParts = deploymentFull.split(": ", 2);
                String deployment = deploymentParts.length > 1 ? deploymentParts[1] : deploymentFull;

                // Process image string to remove "Image: " prefix
                String imageFull = imageCell.getStringCellValue();
                String[] imageParts = imageFull.split(": ", 2);
                String image = imageParts.length > 1 ? imageParts[1] : imageFull;

                // Insert test run and get generated ID
                int runId = insertTestRun("burn_in", jobDate, deployment, image);
                if (runId == -1) {
                    throw new SQLException("Failed to insert test run");
                }

                // Prepare batch insert for test results
                List<TestResultBatch> batchResults = new ArrayList<>();

                // Process metric results
                for (int row = 5; row <= sheet.getLastRowNum(); row++) {
                    Row dataRow = sheet.getRow(row);
                    if (dataRow == null) {
                        continue;
                    }

                    Cell metricCell = dataRow.getCell(0);
                    Cell durationCell = dataRow.getCell(col);

                    if (metricCell == null || durationCell == null) {
                        logger.debug("Skipping row " + row + " due to missing data");
                        continue;
                    }

                    String metric = metricCell.getStringCellValue();
                    Double duration = extractNumericValue(durationCell, row, col);

                    if (duration != null) {
                        batchResults.add(new TestResultBatch(runId, metric, duration));
                    }
                }

                insertTestResultsBatch(batchResults);
            }
        } catch (Exception e) {
            logger.error("Error processing burn-in test file: " + e.getMessage(), e);
            throw new Exception("Error processing burn-in test file", e);
        }
    }

    // Processes regression Excel files with multiple sheets
    void processRegressionFile(String filePath) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new File(filePath))) {
            // Process each sheet (scenario: (median, error, etc.))
            for (Sheet sheet : workbook) {
                String scenarioName = sheet.getSheetName().toLowerCase();

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) {
                    logger.warn("Header row not found in sheet: " + scenarioName);
                    continue;
                }

                // Process columns as separate test runs
                for (int col = 1; col <= headerRow.getLastCellNum(); col++) {
                    Cell dateCell = headerRow.getCell(col);
                    if (dateCell == null) {
                        logger.debug("Skipping empty column " + col + " in sheet " + scenarioName);
                        continue;
                    }

                    String jobDate;
                    if (dateCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(dateCell)) {
                        // Handle date formatted cells
                        java.util.Date date = dateCell.getDateCellValue();
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                        jobDate = sdf.format(date);
                    } else {
                        // Handle string dates
                        jobDate = dateCell.getStringCellValue();
                    }
                    Row deploymentRow = sheet.getRow(1);
                    Row imageRow = sheet.getRow(2);

                    if (deploymentRow == null || imageRow == null) {
                        logger.warn("Missing deployment or image row in sheet " + scenarioName);
                        continue;
                    }

                    Cell deploymentCell = deploymentRow.getCell(col);
                    Cell imageCell = imageRow.getCell(col);

                    if (deploymentCell == null || imageCell == null) {
                        logger.warn("Missing deployment or image data for column " + col + " in sheet " + scenarioName);
                        continue;
                    }

                    // Process deployment string to remove "Deployment: " prefix
                    String deploymentFull = deploymentCell.getStringCellValue();
                    String[] deploymentParts = deploymentFull.split(": ", 2);
                    String deployment = deploymentParts.length > 1 ? deploymentParts[1] : deploymentFull;

                    // Process image string to remove "Image: " prefix
                    String imageFull = imageCell.getStringCellValue();
                    String[] imageParts = imageFull.split(": ", 2);
                    String image = imageParts.length > 1 ? imageParts[1] : imageFull;

                    // Create test run with scenario-specific ID
                    int runId = insertTestRun(scenarioName, jobDate, deployment, image);
                    if (runId == -1) {
                        throw new SQLException("Failed to insert test run");
                    }

                    // Prepare batch insert for test results
                    List<TestResultBatch> batchResults = new ArrayList<>();

                    // Process endpoint results
                    for (int row = 5; row <= sheet.getLastRowNum(); row++) {
                        Row dataRow = sheet.getRow(row);
                        if (dataRow == null) {
                            continue;
                        }

                        Cell endpointCell = dataRow.getCell(0);
                        Cell durationCell = dataRow.getCell(col);

                        if (endpointCell == null || durationCell == null) {
                            logger.debug("Skipping row " + row + " due to missing data in sheet " + scenarioName);
                            continue;
                        }

                        String endpoint = endpointCell.getStringCellValue();
                        Double duration = extractNumericValue(durationCell, row, col);

                        if (duration != null) {
                            batchResults.add(new TestResultBatch(runId, endpoint, duration));
                        }
                    }

                    insertTestResultsBatch(batchResults);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing regression test file: " + e.getMessage(), e);
            throw new Exception("Error processing regression test file", e);
        }
    }

    // Creates TEST_RUN record with Jenkins metadata
    int insertTestRun(String scenarioName, String jobDate, String deployment, String image) throws SQLException {
        // SQL to insert and return the generated ID
        String sql = "INSERT INTO " + TABLE_TEST_RUN + " (SCENARIO_ID, JOB_DATE, DEPLOYMENT_NAME, IMAGE_NAME, BUILD_NUMBER, JENKINS_JOB_NAME) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            String entityType = scenarioName.startsWith("burn_in") ? "metric" : "endpoint";
            int scenarioId = getOrCreateScenario(scenarioName, entityType);
            if (scenarioId == -1) {
                throw new SQLException("Failed to get or create scenario");
            }

            // Process job name string to remove build number suffix
            String jobNameFull = jenkinsJobName;
            String[] jobNameParts = jobNameFull.split(" #", 2);
            String newJobNameString = jobNameParts.length > 1 ? jobNameParts[0] : jobNameFull;

            stmt.setInt(1, scenarioId);
            // Parse date with flexible format handling
            Date sqlDate = parseDateString(jobDate);
            stmt.setDate(2, sqlDate);
            // Trim and truncate strings to max column sizes
            stmt.setString(3, trimToLength(deployment, MAX_DEPLOYMENT_LENGTH));
            stmt.setString(4, trimToLength(image, MAX_IMAGE_LENGTH));
            stmt.setString(5, trimToLength(buildNumber, MAX_BUILD_NUMBER_LENGTH));
            stmt.setString(6, trimToLength(newJobNameString, MAX_JOB_NAME_LENGTH));

            stmt.executeUpdate();

            // Get generated key
            try (Statement idStmt = connection.createStatement(); ResultSet generatedKeys = idStmt.executeQuery("SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY")) {
                if (generatedKeys.next()) {
                    int runId = generatedKeys.getInt(1);
                    return runId;
                } else {
                    throw new SQLException("Failed to retrieve generated key for test run");
                }
            }

        } catch (SQLException e) {
            logger.error("Error inserting test run: " + e.getMessage(), e);
            throw new SQLException("Error inserting test run", e);
        }
    }

    // Helper method to trim and truncate strings to a specified maximum length
    private String trimToLength(String input, int maxLength) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }

        return trimmed.substring(0, maxLength);
    }

    // Maintains TEST_SCENARIO table (prevents duplicates)
    int getOrCreateScenario(String name, String entityType) throws SQLException {
        // Check for existing scenario first
        String checkSql = "SELECT SCENARIO_ID FROM " + TABLE_TEST_SCENARIO + " WHERE NAME = ?";

        try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
            checkStmt.setString(1, name);
            ResultSet rs = checkStmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }

            // If not found, insert a new scenario
            String insertSql = "INSERT INTO " + TABLE_TEST_SCENARIO + " (NAME, ENTITY_TYPE) VALUES (?, ?)";
            try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                insertStmt.setString(1, name);
                insertStmt.setString(2, entityType);
                insertStmt.executeUpdate();

                // Get generated key
                try (Statement idStmt = connection.createStatement(); ResultSet generatedKeys = idStmt.executeQuery("SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY")) {
                    if (generatedKeys.next()) {
                        return generatedKeys.getInt(1);
                    } else {
                        throw new SQLException("Failed to retrieve generated key for scenario");
                    }
                }

            }
        } catch (SQLException e) {
            logger.error("Error getting or creating scenario: " + e.getMessage(), e);
            throw new SQLException("Error getting or creating scenario", e);
        }
    }

    // Class to hold test result data for batch processing
    static class TestResultBatch {

        final int runId;
        final String entityName;
        final double duration;

        public TestResultBatch(int runId, String entityName, double duration) {
            this.runId = runId;
            this.entityName = entityName;
            this.duration = duration;
        }
    }

    // Batch inserts multiple test results at once
    void insertTestResultsBatch(List<TestResultBatch> results) throws SQLException {
        if (results == null || results.isEmpty()) {
            logger.debug("No test results to insert");
            return;
        }

        String sql = "INSERT INTO " + TABLE_TEST_RESULT + " (RUN_ID, ENTITY_NAME, DURATION_MS) VALUES (?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            int batchCount = 0;

            for (TestResultBatch result : results) {
                stmt.setInt(1, result.runId);
                stmt.setString(2, result.entityName);
                stmt.setDouble(3, result.duration);
                stmt.addBatch();

                batchCount++;

                // Execute batch when it reaches the batch size
                if (batchCount % BATCH_SIZE == 0) {
                    stmt.executeBatch();
                    logger.debug("Executed batch of {} test results", BATCH_SIZE);
                }
            }

            // Execute any remaining batched statements
            if (batchCount % BATCH_SIZE != 0) {
                stmt.executeBatch();
                logger.debug("Executed final batch of {} test results", batchCount % BATCH_SIZE);
            }

        } catch (SQLException e) {
            logger.error("Error batch inserting test results: " + e.getMessage(), e);
            throw new SQLException("Error batch inserting test results", e);
        }
    }

    // Helper method to extract a numeric value from a cell, regardless of cell format
    Double extractNumericValue(Cell cell, int rowNum, int colNum) {
        if (cell == null) {
            logger.debug("Cell is null at row " + rowNum + ", column " + colNum);
            return null;
        }

        CellType cellType = cell.getCellType();
        try {
            if (cellType == CellType.NUMERIC) {
                // Direct numeric cell
                return cell.getNumericCellValue();
            } else if (cellType == CellType.STRING) {
                // String cell that might contain a number
                String stringValue = cell.getStringCellValue().trim();
                try {
                    return Double.valueOf(stringValue);
                } catch (NumberFormatException e) {
                    logger.warn("Unable to parse numeric value from text: '" + stringValue
                            + "' at row " + rowNum + ", column " + colNum);
                    return null;
                }
            } else if (cellType == CellType.FORMULA) {
                // Try to get numeric result from formula
                try {
                    return cell.getNumericCellValue();
                } catch (IllegalStateException e) {
                    // If formula resulted in a string, try to parse it
                    String stringValue = cell.getStringCellValue().trim();
                    try {
                        return Double.parseDouble(stringValue);
                    } catch (NumberFormatException ex) {
                        logger.warn("Formula resulted in non-numeric value: '" + stringValue
                                + "' at row " + rowNum + ", column " + colNum);
                        return null;
                    }
                }
            } else {
                // Other types like blank or boolean
                logger.warn("Unsupported cell type at row " + rowNum + ", column " + colNum + ": " + cellType);
                return null;
            }
        } catch (Exception e) {
            logger.warn("Error extracting numeric value at row " + rowNum + ", column " + colNum + ": " + e.getMessage());
            return null;
        }
    }

    // Helper method to parse date strings in various formats
    Date parseDateString(String dateStr) throws SQLException {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            throw new SQLException("Date string is null or empty");
        }

        // Try different date formats with US locale
        String[] dateFormats = {
            "yyyy-MM-dd", // 2023-04-21
            "MMM d yyyy", // Apr 21 2023 (note the single d for day)
            "MMM dd yyyy", // Apr 21 2023
            "MMMM d, yyyy", // April 21, 2023
            "MMMM dd, yyyy", // April 21, 2023
            "d MMM yyyy", // 21 Apr 2023
            "dd MMM yyyy", // 21 Apr 2023
            "MM/dd/yyyy", // 04/21/2023
            "dd/MM/yyyy" // 21/04/2023
        };

        // Try with both US and default locale
        Locale[] locales = {Locale.US, Locale.getDefault()};

        for (Locale locale : locales) {
            for (String format : dateFormats) {
                try {
                    SimpleDateFormat dateFormat = new SimpleDateFormat(format, locale);
                    dateFormat.setLenient(true);
                    java.util.Date parsedDate = dateFormat.parse(dateStr.trim());
                    return new Date(parsedDate.getTime());
                } catch (ParseException e) {
                }
            }
        }

        // If standard formats fail, try a more direct approach with string manipulation
        try {
            // For format like "Apr 21 2023"
            String[] parts = dateStr.split(" ");
            if (parts.length == 3) {
                String month = parts[0];
                String day = parts[1];
                String year = parts[2];

                // Convert month name to number
                int monthNum;
                switch (month.toLowerCase().substring(0, 3)) {
                    case "jan":
                        monthNum = 1;
                        break;
                    case "feb":
                        monthNum = 2;
                        break;
                    case "mar":
                        monthNum = 3;
                        break;
                    case "apr":
                        monthNum = 4;
                        break;
                    case "may":
                        monthNum = 5;
                        break;
                    case "jun":
                        monthNum = 6;
                        break;
                    case "jul":
                        monthNum = 7;
                        break;
                    case "aug":
                        monthNum = 8;
                        break;
                    case "sep":
                        monthNum = 9;
                        break;
                    case "oct":
                        monthNum = 10;
                        break;
                    case "nov":
                        monthNum = 11;
                        break;
                    case "dec":
                        monthNum = 12;
                        break;
                    default:
                        throw new ParseException("Invalid month: " + month, 0);
                }

                // Format as YYYY-MM-DD
                String formattedDate = String.format("%s-%02d-%02d", year, monthNum, Integer.parseInt(day));
                return Date.valueOf(formattedDate);
            }
        } catch (Exception e) {
            logger.warn("Manual date parsing failed for: " + dateStr + " - " + e.getMessage());
        }

        // If we get here, none of the formats worked
        logger.warn("Could not parse date string: " + dateStr);
        throw new SQLException("Invalid date format: " + dateStr);
    }

    void closeConnection() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.error("Error closing connection: " + e.getMessage(), e);
        }
    }
}
