package tech.ydb.jdbc.context;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import tech.ydb.core.Issue;
import tech.ydb.core.Result;
import tech.ydb.core.Status;
import tech.ydb.core.UnexpectedResultException;
import tech.ydb.jdbc.YdbTracer;
import tech.ydb.jdbc.exception.ExceptionFactory;

/**
 *
 * @author Aleksandr Gorshenin
 */
public class YdbValidator {
    private static final Logger LOGGER = Logger.getLogger(YdbValidator.class.getName());

    private final List<Issue> issues = new ArrayList<>();

    public SQLWarning toSQLWarnings() {
        SQLWarning firstWarning = null;
        SQLWarning warning = null;
        for (Issue issue : issues) {
            SQLWarning nextWarning = new SQLWarning(issue.toString(), null, issue.getCode());
            if (firstWarning == null) {
                firstWarning = nextWarning;
            }
            if (warning != null) {
                warning.setNextWarning(nextWarning);
            }
            warning = nextWarning;
        }
        return firstWarning;
    }

    public void addStatusIssues(Status status) {
        this.issues.addAll(Arrays.asList(status.getIssues()));
    }

    public void addStatusIssues(List<Issue> issues) {
        this.issues.addAll(issues);
    }

    public void clearWarnings() {
        this.issues.clear();
    }

    public void execute(String msg, YdbTracer tracer, Supplier<CompletableFuture<Status>> fn) throws SQLException {
        Status status = fn.get().join();
        addStatusIssues(status);

        tracer.trace("<-- " + status.toString());
        if (!status.isSuccess()) {
            LOGGER.log(Level.FINE, "execute problem {0}", status);
            tracer.close();
            throw ExceptionFactory.createException("Cannot execute '" + msg + "' with " + status,
                    new UnexpectedResultException("Unexpected status", status));
        }
    }

    public <R> R call(String msg, Supplier<CompletableFuture<Result<R>>> fn) throws SQLException {
        try {
            Result<R> result = fn.get().join();
            addStatusIssues(result.getStatus());
            return result.getValue();
        } catch (UnexpectedResultException ex) {
            LOGGER.log(Level.FINE, "call problem {0}", ex.getStatus());
            throw ExceptionFactory.createException("Cannot call '" + msg + "' with " + ex.getStatus(), ex);
        }
    }

    public <R> R call(String msg, YdbTracer tracer, Supplier<CompletableFuture<Result<R>>> fn) throws SQLException {
        try {
            Result<R> result = fn.get().join();
            addStatusIssues(result.getStatus());
            tracer.trace("<-- " + result.getStatus().toString());
            return result.getValue();
        } catch (UnexpectedResultException ex) {
            tracer.close();
            LOGGER.log(Level.FINE, "call problem {0}", ex.getStatus());
            throw ExceptionFactory.createException("Cannot call '" + msg + "' with " + ex.getStatus(), ex);
        }
    }
}
