package tech.ydb.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import tech.ydb.jdbc.settings.YdbOperationProperties;
import tech.ydb.table.SchemeClient;
import tech.ydb.table.Session;

public interface YdbConnection extends Connection {

    /**
     * Returns scheme client when required
     *
     * @return scheme client in memoized mode
     */
    Supplier<SchemeClient> getYdbScheme();

    /**
     * Returns current YDB session for this connection
     *
     * @return YDB session
     */
    Session getYdbSession();

    /**
     * Return current YDB transaction, if exists
     *
     * @return YDB transaction ID or null, if no transaction started
     */
    @Nullable
    String getYdbTxId();


    /**
     * Returns operation properties, configured for this connection
     *
     * @return default YDB operation properties
     */
    YdbOperationProperties getYdbProperties();

    //

    @Override
    YdbStatement createStatement() throws SQLException;

    @Override
    YdbPreparedStatement prepareStatement(String sql) throws SQLException;

    @Override
    YdbStatement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException;

    @Override
    YdbPreparedStatement prepareStatement(String sql, int resultSetType,
                                          int resultSetConcurrency) throws SQLException;

    @Override
    YdbStatement createStatement(int resultSetType, int resultSetConcurrency,
                                 int resultSetHoldability) throws SQLException;

    @Override
    YdbPreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                          int resultSetHoldability) throws SQLException;

    @Override
    YdbPreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException;

    @Override
    YdbPreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException;

    @Override
    YdbPreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException;
}
