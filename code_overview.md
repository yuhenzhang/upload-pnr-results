# Code Overview

A detailed overview of the three main Java files in this application, which downloads regression test results from Jenkins and uploads them to a HANA Cloud database.

## File Structure

The application consists of three main Java classes:

- **App.java** - Main entry point and orchestration
- **JenkinsArtifactDownloader.java** - Handles Jenkins API interaction and file downloads
- **HanaDataUploader.java** - Manages database operations and Excel file processing

---

## App.java

**Purpose**: Main application entry point that orchestrates the download and upload process.

**Package**: `com.sap.fpa61`

### Functions:

#### `main(String[] args)`

- **Purpose**: Application entry point that coordinates the entire workflow
- **Flow**:
  1. Loads configuration properties
  2. Downloads artifacts from Jenkins using JenkinsArtifactDownloader
  3. Uploads data to HANA database using HanaDataUploader
  4. Handles errors and exits appropriately

#### `loadProperties()`

- **Purpose**: Helper method to load configuration from `config.properties` file
- **Returns**: Properties object with configuration settings
- **Error Handling**: Logs warnings if config file not found, uses defaults

---

## JenkinsArtifactDownloader.java

**Purpose**: Handles all Jenkins API interactions, authentication, and artifact downloads.

**Package**: `com.sap.fpa61.jenkins`

### Constants:

- `DEFAULT_USERNAME`: Default Jenkins username
- `DEFAULT_SAVE_DIR`: Default download directory
- Required files: `regression_dolphin.xlsx`, `regression_dolphin_burn_in.xlsx`, `burn_in_analysis.xlsx`

### Functions:

#### `downloadArtifacts(String[] args)`

- **Purpose**: Main download orchestration method
- **Process**:
  1. Loads Jenkins credentials from config.properties
  2. Fetches build information from Jenkins API
  3. Downloads all required test result files
  4. Creates download directory if needed
- **Returns**: JSONObject containing Jenkins build metadata

#### `getSaveDirectory(Properties props)`

- **Purpose**: Retrieves save directory from properties with fallback to default
- **Returns**: String path for download directory

#### `fetchJenkinsJson(String urlString, String username, String apiToken)`

- **Purpose**: Makes authenticated HTTP request to Jenkins API
- **Process**:
  1. Creates authenticated HTTP connection
  2. Reads JSON response from Jenkins API endpoint
  3. Parses response into JSONObject
- **Returns**: JSONObject with build and artifact information

#### `downloadFile(String saveDir, String fileUrl, String fileName, String username, String apiToken)`

- **Purpose**: Downloads individual files from Jenkins
- **Process**:
  1. Creates authenticated connection to file URL
  2. Streams file content to local filesystem
  3. Saves file in specified directory

#### `createAuthConnection(String urlString, String username, String apiToken)`

- **Purpose**: Creates HTTP connection with Basic Authentication
- **Process**:
  1. Encodes credentials in Base64
  2. Sets Authorization header
- **Returns**: Configured HttpURLConnection

#### `readResponse(InputStream inputStream)`

- **Purpose**: Utility method to read HTTP response stream into string
- **Returns**: String containing response content

#### `getConfigInputStream()`

- **Purpose**: Gets input stream for config.properties file
- **Returns**: InputStream for configuration file

---

## HanaDataUploader.java

**Purpose**: Manages all database operations including connection, schema creation, and data upload from Excel files.

**Package**: `com.sap.fpa61.db`

### Constants:

- File names: `BURN_IN_FILE`, `REGRESSION_FILE`, `REGRESSION_BURN_IN_FILE`
- Table names: `TABLE_TEST_RUN`, `TABLE_TEST_SCENARIO`, `TABLE_TEST_RESULT`
- Configuration: `BATCH_SIZE`, maximum field lengths

### Constructor:

#### `HanaDataUploader(JSONObject jsonResponse)`

- **Purpose**: Initializes uploader with Jenkins build metadata
- **Extracts**: Job name and build number from Jenkins response

### Main Upload Methods:

#### `uploadAllFiles(String saveDir)`

- **Purpose**: Main controller method for entire upload process
- **Process**:
  1. Establishes HANA database connection
  2. Processes burn-in analysis file
  3. Processes regression test files
  4. Handles partial failures gracefully
  5. Closes database connection

#### `processBurnInAnalysis(String filePath)`

- **Purpose**: Processes burn-in Excel file with special structure
- **Process**:
  1. Opens Excel workbook and finds 'results' sheet
  2. Processes each column as separate test run
  3. Extracts date, deployment, and image information
  4. Creates test run records in database
  5. Batch inserts metric results

#### `processRegressionFile(String filePath)`

- **Purpose**: Processes regression Excel files with multiple sheets
- **Process**:
  1. Iterates through all sheets (scenarios)
  2. Processes each column as separate test run
  3. Extracts metadata from header rows
  4. Creates scenario-specific test runs
  5. Batch inserts endpoint results

### Database Connection Methods:

#### `connectToHana()`

- **Purpose**: Establishes connection to HANA Cloud database
- **Process**:
  1. Loads database credentials from config.properties
  2. Constructs JDBC connection string
  3. Sets connection properties (encryption, certificates)
  4. Executes DDL script to create/update schema
  5. Verifies connection to correct schema

#### `executeDDLScript()`

- **Purpose**: Executes SQL DDL script to create database schema
- **Process**:
  1. Reads DDL script from `src/main/resources/ddl_script.sql`
  2. Splits script into individual SQL statements
  3. Executes each statement separately
  4. Handles quoted content in SQL properly

#### `closeConnection()`

- **Purpose**: Safely closes database connection with error handling

### Data Processing Methods:

#### `insertTestRun(String scenarioName, String jobDate, String deployment, String image)`

- **Purpose**: Creates TEST_RUN record with Jenkins metadata
- **Process**:
  1. Gets or creates scenario ID
  2. Parses and validates job date
  3. Trims strings to maximum column lengths
  4. Inserts run record and returns generated ID

#### `getOrCreateScenario(String name, String entityType)`

- **Purpose**: Maintains TEST_SCENARIO table, prevents duplicates
- **Process**:
  1. Checks if scenario already exists
  2. Creates new scenario if not found
  3. Returns scenario ID for foreign key relationships

#### `insertTestResultsBatch(List<TestResultBatch> results)`

- **Purpose**: Batch inserts multiple test results for performance
- **Process**:
  1. Prepares batch insert statement
  2. Processes results in configurable batch sizes
  3. Executes batches when size threshold reached

### Utility Methods:

#### `extractNumericValue(Cell cell, int rowNum, int colNum)`

- **Purpose**: Extracts numeric values from Excel cells regardless of format
- **Handles**: Numeric cells, string cells with numbers, formula cells, error cases
- **Returns**: Double value or null if extraction fails

#### `parseDateString(String dateStr)`

- **Purpose**: Parses date strings in various formats
- **Supports**: Multiple date formats, different locales, flexible parsing
- **Returns**: SQL Date object for database insertion

#### `splitSqlStatements(String script)`

- **Purpose**: Splits SQL script into individual statements while preserving quoted content
- **Handles**: Semicolon separators, quoted strings, escaped characters
- **Returns**: List of individual SQL statements

#### `trimToLength(String input, int maxLength)`

- **Purpose**: Trims and truncates strings to fit database column constraints
- **Returns**: String trimmed to maximum allowed length

### Inner Classes:

#### `TestResultBatch`

- **Purpose**: Data class for holding test result information during batch processing
- **Fields**: runId, entityName, duration
- **Used**: For efficient batch insertions

---

## Application Flow Summary

1. **App.main()** loads configuration and orchestrates the process
2. **JenkinsArtifactDownloader** authenticates with Jenkins, fetches build info, and downloads Excel files
3. **HanaDataUploader** connects to database, processes Excel files, and inserts structured data
4. Each Excel column becomes a test run, each row becomes a test result
5. The schema supports both burn-in metrics and regression endpoints through a flexible design

The application is designed for robustness with comprehensive error handling, batch processing for performance, and flexible date/data parsing to handle various Excel formats.
