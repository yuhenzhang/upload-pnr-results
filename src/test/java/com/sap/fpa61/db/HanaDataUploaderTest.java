package com.sap.fpa61.db;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import org.mockito.Mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HanaDataUploaderTest {

    private HanaDataUploader uploader;
    private JSONObject jsonResponse;

    @Mock
    private Connection mockConnection;

    @Mock
    private Statement mockStatement;

    @Mock
    private PreparedStatement mockPreparedStatement;

    @Mock
    private ResultSet mockResultSet;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        jsonResponse = new JSONObject();
        jsonResponse.put("fullDisplayName", "TestJob #42");
        jsonResponse.put("id", "42");
        uploader = spy(new HanaDataUploader(jsonResponse));
        uploader.connection = mockConnection;
    }

    @Test
    void testSplitSqlStatements() {
        String script = "CREATE TABLE X; INSERT INTO X VALUES ('a;b'); COMMIT;";
        assertEquals(3, uploader.splitSqlStatements(script).size());
    }

    @Test
    void testParseDateStringValidFormats() throws SQLException {
        assertEquals(Date.valueOf("2023-01-15"), uploader.parseDateString("2023-01-15"));
        assertEquals(Date.valueOf("2023-04-05"), uploader.parseDateString("Apr 5 2023"));
        assertEquals(Date.valueOf("2023-04-21"), uploader.parseDateString("April 21, 2023"));
    }

    @Test
    void testParseDateStringInvalid() {
        assertThrows(SQLException.class, () -> uploader.parseDateString("bad-date"));
    }

    @Test
    void testExtractNumericValue_numericCell() {
        Cell cell = mock(Cell.class);
        when(cell.getCellType()).thenReturn(CellType.NUMERIC);
        when(cell.getNumericCellValue()).thenReturn(42.0);
        assertEquals(42.0, uploader.extractNumericValue(cell, 0, 0));
    }

    @Test
    void testExtractNumericValue_stringParsable() {
        Cell cell = mock(Cell.class);
        when(cell.getCellType()).thenReturn(CellType.STRING);
        when(cell.getStringCellValue()).thenReturn("123.4");
        assertEquals(123.4, uploader.extractNumericValue(cell, 0, 0));
    }

    @Test
    void testExtractNumericValue_stringNonParsable() {
        Cell cell = mock(Cell.class);
        when(cell.getCellType()).thenReturn(CellType.STRING);
        when(cell.getStringCellValue()).thenReturn("abc");
        assertNull(uploader.extractNumericValue(cell, 0, 0));
    }

    @Test
    void testGetOrCreateScenario_existing() throws SQLException {
        PreparedStatement check = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(startsWith("SELECT SCENARIO_ID"))).thenReturn(check);
        when(check.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(5);
        assertEquals(5, uploader.getOrCreateScenario("s1", "metric"));
    }

    @Test
    void testGetOrCreateScenario_new() throws SQLException {
        PreparedStatement checkStmt = mock(PreparedStatement.class);
        ResultSet selectResultSet = mock(ResultSet.class);
        when(mockConnection.prepareStatement("SELECT SCENARIO_ID FROM TEST_SCENARIO WHERE NAME = ?"))
                .thenReturn(checkStmt);
        when(checkStmt.executeQuery()).thenReturn(selectResultSet);
        when(selectResultSet.next()).thenReturn(false);

        PreparedStatement insertStmt = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement("INSERT INTO TEST_SCENARIO (NAME, ENTITY_TYPE) VALUES (?, ?)"))
                .thenReturn(insertStmt);

        Statement idStmt = mock(Statement.class);
        ResultSet identityResultSet = mock(ResultSet.class);
        when(mockConnection.createStatement()).thenReturn(idStmt);
        when(idStmt.executeQuery("SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY")).thenReturn(identityResultSet);
        when(identityResultSet.next()).thenReturn(true);
        when(identityResultSet.getInt(1)).thenReturn(9);

        assertEquals(9, uploader.getOrCreateScenario("s2", "endpoint"));
    }

    @Test
    void testInsertTestRun_success() throws Exception {
        PreparedStatement pstm = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(startsWith("INSERT INTO TEST_RUN"))).thenReturn(pstm);

        Statement idStmt = mock(Statement.class);
        when(mockConnection.createStatement()).thenReturn(idStmt);
        when(idStmt.executeQuery("SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(15);

        doReturn(3).when(uploader).getOrCreateScenario(anyString(), anyString());

        assertEquals(15, uploader.insertTestRun("burn_in", "2025-04-24", "d", "i"));
        verify(pstm).setString(6, "TestJob");
    }

    @Test
    void testInsertTestResult_success() throws Exception {
        PreparedStatement pstm = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(startsWith("INSERT INTO TEST_RESULT"))).thenReturn(pstm);

        // Create a batch with a single test result
        List<HanaDataUploader.TestResultBatch> batch = new ArrayList<>();
        batch.add(new HanaDataUploader.TestResultBatch(1, "e1", 100.0));

        uploader.insertTestResultsBatch(batch);

        // Verify the prepared statement was set with expected values
        verify(pstm).setInt(1, 1);     // RUN_ID
        verify(pstm).setString(2, "e1"); // ENTITY_NAME
        verify(pstm).setDouble(3, 100.0); // DURATION_MS
        verify(pstm).executeBatch();
    }

    @Test
    void testUploadAllFiles_invokesProcesses() throws Exception {
        doAnswer(invocation -> {
            uploader.connection = mockConnection;
            return null;
        }).when(uploader).connectToHana();
        doNothing().when(uploader).processBurnInAnalysis(anyString());
        doNothing().when(uploader).processRegressionFile(anyString());
        uploader.uploadAllFiles("/x/");
        verify(uploader).processBurnInAnalysis("/x/burn_in_analysis.xlsx");
        verify(uploader, times(2)).processRegressionFile(endsWith(".xlsx"));
    }

    @Test
    void testProcessBurnInAnalysis_parsesAndInserts() throws Exception {
        Path file = tempDir.resolve("burn_in_analysis.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("results");
            Row h = sheet.createRow(0);
            Cell dateCell = h.createCell(1);
            dateCell.setCellValue("2025-04-24");
            sheet.createRow(1).createCell(1).setCellValue("Deployment: v1");
            sheet.createRow(2).createCell(1).setCellValue("Image: img1");
            Row data = sheet.createRow(5);
            data.createCell(0).setCellValue("mA");
            data.createCell(1).setCellValue(11.0);
            try (OutputStream os = Files.newOutputStream(file)) {
                wb.write(os);
            }
        }

        doReturn(55).when(uploader).insertTestRun(anyString(), anyString(), anyString(), anyString());
        doNothing().when(uploader).insertTestResultsBatch(any());

        uploader.processBurnInAnalysis(file.toString());

        // Capture the batch list that was passed to insertTestResultsBatch
        ArgumentCaptor<List<HanaDataUploader.TestResultBatch>> batchCaptor
                = ArgumentCaptor.forClass((Class) List.class);
        verify(uploader).insertTestResultsBatch(batchCaptor.capture());

        // Check the batch contains our test data
        List<HanaDataUploader.TestResultBatch> batch = batchCaptor.getValue();
        assertEquals(1, batch.size());
        assertEquals(55, batch.get(0).runId);
        assertEquals("mA", batch.get(0).entityName);
        assertEquals(11.0, batch.get(0).duration);

        verify(uploader).insertTestRun("burn_in", "2025-04-24", "v1", "img1");
    }

    @Test
    void testProcessRegressionFile_parsesAndInserts() throws Exception {
        Path file = tempDir.resolve("regression.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("median");
            Row h = sheet.createRow(0);
            Cell dateCell = h.createCell(1);
            dateCell.setCellValue("2025-04-24");
            sheet.createRow(1).createCell(1).setCellValue("Deployment: v1");
            sheet.createRow(2).createCell(1).setCellValue("Image: img1");
            Row data = sheet.createRow(5);
            data.createCell(0).setCellValue("eA");
            data.createCell(1).setCellValue(22.0);
            try (OutputStream os = Files.newOutputStream(file)) {
                wb.write(os);
            }
        }

        doReturn(66).when(uploader).insertTestRun(eq("median"), anyString(), anyString(), anyString());
        doNothing().when(uploader).insertTestResultsBatch(any());

        uploader.processRegressionFile(file.toString());

        // Capture the batch list that was passed to insertTestResultsBatch
        ArgumentCaptor<List<HanaDataUploader.TestResultBatch>> batchCaptor
                = ArgumentCaptor.forClass((Class) List.class);
        verify(uploader).insertTestResultsBatch(batchCaptor.capture());

        // Check the batch contains our test data
        List<HanaDataUploader.TestResultBatch> batch = batchCaptor.getValue();
        assertEquals(1, batch.size());
        assertEquals(66, batch.get(0).runId);
        assertEquals("eA", batch.get(0).entityName);
        assertEquals(22.0, batch.get(0).duration);

        verify(uploader).insertTestRun("median", "2025-04-24", "v1", "img1");
    }
}
