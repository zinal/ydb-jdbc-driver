package tech.ydb.jdbc.impl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tech.ydb.jdbc.YdbConnection;
import tech.ydb.jdbc.YdbConst;
import tech.ydb.jdbc.YdbDatabaseMetaData;
import tech.ydb.jdbc.YdbDriver;
import tech.ydb.jdbc.YdbPreparedStatement;
import tech.ydb.jdbc.exception.YdbNonRetryableException;
import tech.ydb.jdbc.impl.helper.JdbcConnectionExtention;
import tech.ydb.jdbc.impl.helper.TableAssert;
import tech.ydb.jdbc.impl.helper.TestConsts;
import tech.ydb.table.values.ListType;
import tech.ydb.table.values.ListValue;
import tech.ydb.table.values.PrimitiveType;
import tech.ydb.table.values.PrimitiveValue;
import tech.ydb.test.junit5.YdbHelperExtention;

public class YdbConnectionImplTest {
    @RegisterExtension
    private static final YdbHelperExtention ydb = new YdbHelperExtention();

    @RegisterExtension
    private static final JdbcConnectionExtention jdbc = new JdbcConnectionExtention(ydb);

    private static final String TEST_TABLE = "ydb_connection_test";

    private static final TableAssert simpleTable = new TableAssert();
    private static final TableAssert.IntColumn simpleResult = simpleTable.addIntColumn("column0", "Int32");

    @BeforeAll
    public static void createTable() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement();) {
            // create simple tables
            statement.execute("--jdbc:SCHEME\n" + TestConsts.allTypesTable(TEST_TABLE));
        }
    }

    @AfterAll
    public static void dropTable() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement();) {
            // drop simple tables
            statement.execute("--jdbc:SCHEME\n drop table " + TEST_TABLE + ";");
        }
    }

    @BeforeEach
    public void checkTransactionState() throws SQLException {
        Assertions.assertNull(currentTxId(), "Transaction must be empty before test");
    }

    @AfterEach
    public void checkTableIsEmpty() throws SQLException {
        if (jdbc.connection().isClosed()) {
            return;
        }

        try (Statement statement = jdbc.connection().createStatement();) {
            try (ResultSet result = statement.executeQuery("select * from " + TEST_TABLE)) {
                Assertions.assertFalse(result.next(), "Table must be empty after test");
            }
        }
        jdbc.connection().commit(); // MUST be auto rollbacked
        jdbc.connection().close();
    }

    private void assertResult(int value, ResultSet rs) throws SQLException {
        simpleTable.check(rs).assertMetaColumns()
                .nextRow(simpleResult.eq(value)).assertAll()
                .assertNoRows();
    }

    private void assertSQLFeatureNotSupported(String message, Executable exec) {
        SQLFeatureNotSupportedException ex = Assertions.assertThrows(SQLFeatureNotSupportedException.class, exec,
                "Invalid statement must throw SQLFeatureNotSupportedException"
        );
        Assertions.assertEquals(message, ex.getMessage());
    }

    private void assertYdbNonRetryable(String message, Executable exec) {
        YdbNonRetryableException ex = Assertions.assertThrows(YdbNonRetryableException.class, exec,
                "Invalid statement must throw YdbNonRetryableException"
        );
        Assertions.assertTrue(ex.getMessage().contains(message), "Exception doesn't contain message");
    }

    private void assertSQLException(String message, Executable exec) {
        SQLException ex = Assertions.assertThrows(SQLException.class, exec,
                "Invalid statement must throw SQLException"
        );
        Assertions.assertTrue(ex.getMessage().contains(message), "Exception doesn't contain message");
    }

    private String currentTxId() throws SQLException {
        return jdbc.connection().unwrap(YdbConnection.class).getYdbTxId();
    }

    @Test
    public void connectionUnwrapTest() throws SQLException {
        Assertions.assertTrue(jdbc.connection().isWrapperFor(YdbConnection.class));
        Assertions.assertSame(jdbc.connection(), jdbc.connection().unwrap(YdbConnection.class));

        Assertions.assertFalse(jdbc.connection().isWrapperFor(YdbDatabaseMetaData.class));

        SQLException ex = Assertions.assertThrows(SQLException.class,
                () -> jdbc.connection().unwrap(YdbDatabaseMetaData.class));
        Assertions.assertEquals("Cannot unwrap to interface tech.ydb.jdbc.YdbDatabaseMetaData", ex.getMessage());
    }

    @Test
    public void createStatement() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            assertResult(4, statement.executeQuery("select 1 + 3"));
        }
        try (Statement statement = jdbc.connection().createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            assertResult(5, statement.executeQuery("select 2 + 3"));
        }
        try (Statement statement = jdbc.connection().createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            assertResult(3, statement.executeQuery("select 2 + 1"));
        }
        try (Statement statement = jdbc.connection().createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT)) {
            assertResult(2, statement.executeQuery("select 1 + 1"));
        }
    }

    @Test
    public void createStatementInvalid() {
        assertSQLFeatureNotSupported(
                "resultSetType must be ResultSet.TYPE_FORWARD_ONLY or ResultSet.TYPE_SCROLL_INSENSITIVE",
                () -> jdbc.connection().createStatement(
                        ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT
                )
        );

        assertSQLFeatureNotSupported(
                "resultSetConcurrency must be ResultSet.CONCUR_READ_ONLY",
                () -> jdbc.connection().createStatement(
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE, ResultSet.HOLD_CURSORS_OVER_COMMIT
                )
        );

        assertSQLFeatureNotSupported(
                "resultSetHoldability must be ResultSet.HOLD_CURSORS_OVER_COMMIT",
                () -> jdbc.connection().createStatement(
                        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT
                )
        );
    }

    @Test
    public void prepareStatement() throws SQLException {
        try (PreparedStatement statement = jdbc.connection().prepareStatement("select 1 + 3")) {
            assertResult(4, statement.executeQuery());
        }
        try (PreparedStatement statement = jdbc.connection().prepareStatement("select 2 + 3",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            assertResult(5, statement.executeQuery());
        }
        try (PreparedStatement statement = jdbc.connection().prepareStatement("select 2 + 1",
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            assertResult(3, statement.executeQuery());
        }
        try (PreparedStatement statement = jdbc.connection().prepareStatement("select 1 + 1",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT)) {
            assertResult(2, statement.executeQuery());
        }
    }

    @Test
    public void prepareStatementInvalid() throws SQLException {
        assertSQLFeatureNotSupported("Auto-generated keys are not supported",
                () -> jdbc.connection().prepareStatement("select 2 + 2", new int[] {})
        );

        assertSQLFeatureNotSupported("Auto-generated keys are not supported",
                () -> jdbc.connection().prepareStatement("select 2 + 2", new String[] {})
        );

        assertSQLFeatureNotSupported("Auto-generated keys are not supported",
                () -> jdbc.connection().prepareStatement("select 2 + 2", Statement.RETURN_GENERATED_KEYS)
        );

        assertSQLFeatureNotSupported(
                "resultSetType must be ResultSet.TYPE_FORWARD_ONLY or ResultSet.TYPE_SCROLL_INSENSITIVE",
                () -> jdbc.connection().prepareStatement("select 2 + 2",
                        ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT
                )
        );

        assertSQLFeatureNotSupported(
                "resultSetConcurrency must be ResultSet.CONCUR_READ_ONLY",
                () -> jdbc.connection().prepareStatement("select 2 + 2",
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE, ResultSet.HOLD_CURSORS_OVER_COMMIT
                )
        );

        assertSQLFeatureNotSupported(
                "resultSetHoldability must be ResultSet.HOLD_CURSORS_OVER_COMMIT",
                () -> jdbc.connection().prepareStatement("select 2 + 2",
                        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT
                )
        );
    }

    @Test
    public void prepareCallNotSupported() {
        assertSQLFeatureNotSupported("Prepared calls are not supported",
                () -> jdbc.connection().prepareCall("select 2 + 2")
        );

        assertSQLFeatureNotSupported("Prepared calls are not supported",
                () -> jdbc.connection().prepareCall("select 2 + 2",
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
        );

        assertSQLFeatureNotSupported("Prepared calls are not supported",
                () -> jdbc.connection().prepareCall("select 2 + 2",
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT)
        );
    }

    @Test
    public void nativeSQL() throws SQLException {
        Assertions.assertEquals("select ? + ?", jdbc.connection().nativeSQL("select ? + ?"));
    }

    @Test
    @Disabled("WRONG LOGIC")
    public void isValid() throws SQLException {
        Assertions.assertTrue(jdbc.connection().isValid(1));
        YdbDriver.getConnectionsCache().close();
        Assertions.assertFalse(jdbc.connection().isValid(1));
    }

    @Test
    public void clientInfo() throws SQLException {
        Assertions.assertEquals(new Properties(), jdbc.connection().getClientInfo());

        Properties properties = new Properties();
        properties.setProperty("key", "value");
        jdbc.connection().setClientInfo(properties);

        Assertions.assertEquals(new Properties(), jdbc.connection().getClientInfo());
        jdbc.connection().setClientInfo("key", "value");

        Assertions.assertEquals(new Properties(), jdbc.connection().getClientInfo());
        Assertions.assertNull(jdbc.connection().getClientInfo("key"));
    }

    @Test
    public void invalidSQLCancelTransaction() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            statement.execute("select 2 + 2");
            String txId = currentTxId();
            Assertions.assertNotNull(txId);

            assertYdbNonRetryable("Column reference 'x' (S_ERROR)", () -> statement.execute("select 2 + x"));

            statement.execute("select 2 + 2");
            Assertions.assertNotNull(currentTxId());
            Assertions.assertNotEquals(txId, currentTxId());
        }
    }

    @Test
    public void autoCommit() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            statement.execute("select 2 + 2");
            String txId = currentTxId();
            Assertions.assertNotNull(txId);

            Assertions.assertFalse(jdbc.connection().getAutoCommit());

            jdbc.connection().setAutoCommit(false);
            Assertions.assertFalse(jdbc.connection().getAutoCommit());
            Assertions.assertEquals(txId, currentTxId());

            jdbc.connection().setAutoCommit(true);
            Assertions.assertTrue(jdbc.connection().getAutoCommit());
            Assertions.assertNull(currentTxId());

            statement.execute("select 2 + 2");
            Assertions.assertNull(currentTxId());

            statement.execute("select 2 + 2");
            Assertions.assertNull(currentTxId());

            jdbc.connection().setAutoCommit(false);
            Assertions.assertFalse(jdbc.connection().getAutoCommit());
            Assertions.assertNull(currentTxId());
        }
    }

    @Test
    public void commit() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            Assertions.assertTrue(statement.execute("select 2 + 2"));
            String txId = currentTxId();
            Assertions.assertNotNull(txId);

            Assertions.assertTrue(statement.execute("select 2 + 2"));
            Assertions.assertEquals(txId, currentTxId());

            Assertions.assertTrue(statement.execute("select * from " + TEST_TABLE));
            Assertions.assertEquals(txId, currentTxId());

            Assertions.assertFalse(statement.execute("upsert into " + TEST_TABLE + "(key, c_Text) values (1, '2')"));
            Assertions.assertEquals(txId, currentTxId());

            Assertions.assertTrue(statement.execute("select 2 + 2"));
            Assertions.assertEquals(txId, currentTxId());

            jdbc.connection().commit();
            Assertions.assertNull(currentTxId());

            jdbc.connection().commit(); // does nothing
            Assertions.assertNull(currentTxId());

            try (ResultSet result = statement.executeQuery("select * from " + TEST_TABLE)) {
                Assertions.assertTrue(result.next());
            }

            // clean simple tables
            statement.execute("delete from " + TEST_TABLE + ";");
            jdbc.connection().commit();
        }
    }

    @Test
    public void rollback() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            Assertions.assertTrue(statement.execute("select 2 + 2"));
            String txId = currentTxId();
            Assertions.assertNotNull(txId);

            Assertions.assertTrue(statement.execute("select 2 + 2"));
            Assertions.assertEquals(txId, currentTxId());

            Assertions.assertTrue(statement.execute("select * from " + TEST_TABLE));
            Assertions.assertEquals(txId, currentTxId());

            Assertions.assertFalse(statement.execute("upsert into " + TEST_TABLE + "(key, c_Text) values (1, '2')"));
            Assertions.assertEquals(txId, currentTxId());

            Assertions.assertTrue(statement.execute("select 2 + 2"));
            Assertions.assertEquals(txId, currentTxId());

            jdbc.connection().rollback();
            Assertions.assertNull(currentTxId());

            jdbc.connection().rollback(); // does nothing
            Assertions.assertNull(currentTxId());

            try (ResultSet result = statement.executeQuery("select * from " + TEST_TABLE)) {
                Assertions.assertFalse(result.next());
            }
        }
    }

    @Test
    public void commitInvalidTx() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            statement.execute("upsert into " + TEST_TABLE + "(key, c_Text) values (1, '2')");
            statement.execute("upsert into " + TEST_TABLE + "(key, c_Text) values (1, '2')");

            assertYdbNonRetryable("Data modifications previously made to table",
                    () -> statement.executeQuery("select * from " + TEST_TABLE));

            Assertions.assertNull(currentTxId());

            jdbc.connection().commit(); // Nothing to commit, transaction was rolled back already
            Assertions.assertNull(currentTxId());
        }
    }

    @Test
    public void rollbackInvalidTx() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            ResultSet result = statement.executeQuery("select * from " + TEST_TABLE);
            Assertions.assertFalse(result.next());

            statement.execute("insert into " + TEST_TABLE + "(key, c_Text) values (1, '2')");

            assertYdbNonRetryable("Data modifications previously made to table",
                    () -> statement.executeQuery("select * from " + TEST_TABLE));

            Assertions.assertNull(currentTxId());
            jdbc.connection().rollback();
            Assertions.assertNull(currentTxId());
        }
    }

    @Test
    public void closeTest() throws SQLException {
        Assertions.assertFalse(jdbc.connection().isClosed());
        jdbc.connection().close();
        Assertions.assertTrue(jdbc.connection().isClosed());
        jdbc.connection().close(); // no effect
    }

    @Test
    public void getMetaData() throws SQLException {
        DatabaseMetaData metaData = jdbc.connection().getMetaData();
        Assertions.assertNotNull(metaData);
    }

    @Test
    public void readOnly() throws SQLException {
        Assertions.assertFalse(jdbc.connection().isReadOnly());
        Assertions.assertEquals(Connection.TRANSACTION_SERIALIZABLE, jdbc.connection().getTransactionIsolation());

        jdbc.connection().setReadOnly(true);

        Assertions.assertTrue(jdbc.connection().isReadOnly());
        Assertions.assertEquals(Connection.TRANSACTION_REPEATABLE_READ, jdbc.connection().getTransactionIsolation());

        jdbc.connection().setReadOnly(false);
        Assertions.assertFalse(jdbc.connection().isReadOnly());
        Assertions.assertEquals(Connection.TRANSACTION_SERIALIZABLE, jdbc.connection().getTransactionIsolation());
    }

    @Test
    public void catalog() throws SQLException {
        Assertions.assertNull(jdbc.connection().getCatalog());
        jdbc.connection().setCatalog("any"); // catalogs are not supported
        Assertions.assertNull(jdbc.connection().getCatalog());
    }

    @Test
    public void schema() throws SQLException {
        Assertions.assertNull(jdbc.connection().getSchema());
        jdbc.connection().setSchema("test"); // schemas are not supported
        Assertions.assertNull(jdbc.connection().getSchema());
    }

    @ParameterizedTest(name = "Check supported isolation level {0}")
    @ValueSource(ints = {
        YdbConst.TRANSACTION_SERIALIZABLE_READ_WRITE, // 8
        YdbConst.ONLINE_CONSISTENT_READ_ONLY, // 4
        YdbConst.ONLINE_INCONSISTENT_READ_ONLY, // 2
        YdbConst.STALE_CONSISTENT_READ_ONLY // 3
    })
    public void supportedTransactionIsolations(int level) throws SQLException {
        jdbc.connection().setTransactionIsolation(level);
        Assertions.assertEquals(level, jdbc.connection().getTransactionIsolation());

        try (Statement statement = jdbc.connection().createStatement()) {
            assertResult(4, statement.executeQuery("select 2 + 2"));
        }
    }

    @ParameterizedTest(name = "Check supported isolation level {0}")
    @ValueSource(ints = { 0, 1, /*2, 3, 4,*/ 5, 6, 7, /*8,*/ 9, 10 })
    public void unsupportedTransactionIsolations(int level) throws SQLException {
        assertSQLException("Unsupported transaction level: " + level,
                () -> jdbc.connection().setTransactionIsolation(level)
        );
    }

    @Test
    public void clearWarnings() throws SQLException {
        // TODO: generate warnings
        Assertions.assertNull(jdbc.connection().getWarnings());
        jdbc.connection().clearWarnings();
        Assertions.assertNull(jdbc.connection().getWarnings());
    }

    @Test
    public void typeMap() throws SQLException {
        Assertions.assertTrue(jdbc.connection().getTypeMap().isEmpty());

        Map<String, Class<?>> newMap = new HashMap<>();
        newMap.put("type1", String.class);
        jdbc.connection().setTypeMap(newMap);

        // not implemented
        Assertions.assertTrue(jdbc.connection().getTypeMap().isEmpty());
    }

    @Test
    public void holdability() throws SQLException {
        Assertions.assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, jdbc.connection().getHoldability());
        jdbc.connection().setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);

        assertSQLFeatureNotSupported("resultSetHoldability must be ResultSet.HOLD_CURSORS_OVER_COMMIT",
                () -> jdbc.connection().setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT));
    }

    @Test
    public void networkTimeout() throws SQLException {
        Assertions.assertEquals(0, jdbc.connection().getNetworkTimeout());
        assertSQLFeatureNotSupported("Set network timeout is not supported yet",
                () -> jdbc.connection().setNetworkTimeout(null, 1));
    }

    @Test
    public void savepoints() {
        assertSQLFeatureNotSupported("Savepoints are not supported", () -> jdbc.connection().setSavepoint());
        assertSQLFeatureNotSupported("Savepoints are not supported", () -> jdbc.connection().setSavepoint("name"));
        assertSQLFeatureNotSupported("Savepoints are not supported", () -> jdbc.connection().releaseSavepoint(null));
    }

    @Test
    public void unsupportedTypes() {
        assertSQLFeatureNotSupported("Clobs are not supported", () -> jdbc.connection().createClob());
        assertSQLFeatureNotSupported("Blobs are not supported", () -> jdbc.connection().createBlob());
        assertSQLFeatureNotSupported("NClobs are not supported", () -> jdbc.connection().createNClob());
        assertSQLFeatureNotSupported("SQLXMLs are not supported", () -> jdbc.connection().createSQLXML());
        assertSQLFeatureNotSupported("Arrays are not supported",
                () -> jdbc.connection().createArrayOf("type", new Object[] { })
        );
        assertSQLFeatureNotSupported("Structs are not supported",
                () -> jdbc.connection().createStruct("type", new Object[] { })
        );
    }

    @Test
    public void abort() {
        assertSQLFeatureNotSupported("Abort operation is not supported yet",  () -> jdbc.connection().abort(null));
    }
    @Test
    public void testDDLInsideTransaction() throws SQLException {
        String tempTable = "temp_" + TEST_TABLE;
        try (Statement statement = jdbc.connection().createStatement()) {
            statement.execute("upsert into " + TEST_TABLE + "(key, c_Text) values (1, '2')");
            statement.execute("--jdbc:SCHEME\n"
                    + "create table " + tempTable + "(id Int32, value Int32, primary key(id))");

            try {
                // No commits in case of ddl
                assertYdbNonRetryable("Data modifications previously made to table",
                        () -> statement.executeQuery("select * from " + TEST_TABLE));
            } finally {
                statement.execute("--jdbc:SCHEME\ndrop table " + tempTable);
            }
        }
    }

    @Test
    public void testWarningInIndexUsage() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            statement.execute("--jdbc:SCHEME\n" +
                    "create table unit_0_indexed (" +
                    "id Int32, value Int32, " +
                    "primary key (id), " +
                    "index idx_value global on (value))");

            String query = "--!syntax_v1\n" +
                    "declare $list as List<Int32>;\n" +
                    "select * from unit_0_indexed view idx_value where value in $list;";

            ListValue value = ListType.of(PrimitiveType.Int32).newValue(
                    Arrays.asList(PrimitiveValue.newInt32(1), PrimitiveValue.newInt32(2)));
            try (PreparedStatement ps = jdbc.connection().prepareStatement(query)) {
                ps.unwrap(YdbPreparedStatement.class).setObject("list", value);

                ResultSet rs = ps.executeQuery();
                Assertions.assertFalse(rs.next());

                SQLWarning warnings = ps.getWarnings();
                Assertions.assertNotNull(warnings);

                Assertions.assertEquals("#1030 Type annotation (S_WARNING)\n"
                        + "  1:3 - 1:3: At function: RemovePrefixMembers, At function: RemoveSystemMembers, "
                        + "At function: PersistableRepr, At function: SqlProject (S_WARNING)\n"
                        + "  35:3 - 35:3: At function: Filter, At function: Coalesce (S_WARNING)\n"
                        + "  51:3 - 51:3: At function: SqlIn (S_WARNING)\n"
                        + "  51:3 - 51:3: #1108 IN may produce unexpected result when used with nullable arguments. "
                        + "Consider adding 'PRAGMA AnsiInForEmptyOrNullableItemsCollections;' (S_WARNING)",
                        warnings.getMessage());
                Assertions.assertNull(warnings.getNextWarning());
            }
        }
    }

    @Test
    public void testAnsiLexer() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            ResultSet rs = statement.executeQuery("--!ansi_lexer\n" +
                    "select 'string value' as \"name with space\"");
            Assertions.assertTrue(rs.next());
            Assertions.assertEquals("string value", rs.getString("name with space"));
        }
    }

    @Test
    public void testAnsiLexerForIdea() throws SQLException {
        try (Statement statement = jdbc.connection().createStatement()) {
            statement.execute("upsert into " + TEST_TABLE + "(key, c_Text) values (1, '2')");
            jdbc.connection().commit();

            // TODO: Must be "unit_1" t, see YQL-12618
            try (ResultSet rs = statement.executeQuery("--!ansi_lexer\n" +
                    "select t.c_Text from \"" + TEST_TABLE + "\" as t where t.key = 1")) {
                Assertions.assertTrue(rs.next());
                Assertions.assertEquals("2", rs.getString("c_Text"));
            }

            try (ResultSet rs = statement.executeQuery("--!ansi_lexer\n" +
                    "select t.c_Text from " + TEST_TABLE + " t where t.key = 1")) {
                Assertions.assertTrue(rs.next());
                Assertions.assertEquals("2", rs.getString("c_Text"));
            }
        } finally {
            try (Statement statement = jdbc.connection().createStatement()) {
                statement.execute("delete from " + TEST_TABLE);
                jdbc.connection().commit();
            }
        }
    }

    @ParameterizedTest(name = "Check unsupported by storage type {0}")
    @ValueSource(strings = {
        "Int8",
        "Int16",
        "Uint16",
        "Uuid",
        "TzDate",
        "TzDatetime",
        "TzTimestamp",
    })
    public void testUnsupportedByStorageTableTypes(String type) throws SQLException {
        String tableName = "unsupported_" + type;
        String sql = "--jdbc:SCHEME\n"
                + "create table " + tableName + " (key Int32, payload " + type + ", primary key(key))";

        try (Statement statement = jdbc.connection().createStatement()) {
            assertYdbNonRetryable("is not supported by storage", () -> statement.execute(sql));
        }
    }

    @ParameterizedTest(name = "Check unsupported complex type {0}")
    @ValueSource(strings = {
        "List<Int32>",
        "Struct<name:Int32>",
        "Tuple<Int32>",
        "Dict<Text,Int32>",
    })
    public void testUnsupportedComplexTypes(String type) throws SQLException {
        String tableName = "unsupported_" + type.replaceAll("[^a-zA-Z0-9]", "");
        String sql = "--jdbc:SCHEME\n"
                + "create table " + tableName + " (key Int32, payload " + type + ", primary key(key))";

        try (Statement statement = jdbc.connection().createStatement()) {
            assertYdbNonRetryable("Only core YQL data types are currently supported", () -> statement.execute(sql));
        }
    }
}
