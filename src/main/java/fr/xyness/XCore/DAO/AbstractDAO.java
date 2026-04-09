package fr.xyness.XCore.DAO;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import fr.xyness.XCore.XCore;

/**
 * Abstract base class for all Data Access Objects.
 * <p>
 * Provides shared infrastructure: database connection retrieval from the
 * HikariCP pool and async execution helpers built on {@link CompletableFuture}.
 * All async operations have a configurable timeout to prevent indefinite hangs.
 * </p>
 */
public abstract class AbstractDAO {

	/** Reference to the main plugin instance for accessing the data source. */
    protected final XCore main;

	/** Dedicated executor used to run database operations off the main thread. */
    private final ExecutorService executor;

    /** Timeout in seconds for database operations. */
    private static final long DB_TIMEOUT_SECONDS = 30;

	/**
	 * Creates a new DAO with the given plugin instance and executor.
	 *
	 * @param main     The main plugin instance.
	 * @param executor The executor service for async task submission.
	 */
    public AbstractDAO(XCore main, ExecutorService executor) {
        this.main = main;
        this.executor = executor;
    }

	/**
	 * Obtains a database connection from the HikariCP pool.
	 *
	 * @return A pooled {@link Connection}.
	 * @throws SQLException If a connection cannot be acquired.
	 */
    protected Connection getConnection() throws SQLException {
        return main.getDataSource().getConnection();
    }

	/**
	 * Executes a value-returning task asynchronously on the DAO executor.
	 *
	 * @param <T>  The return type.
	 * @param task The callable task to execute.
	 * @return A {@link CompletableFuture} that completes with the task result.
	 */
    protected <T> CompletableFuture<T> supplyAsync(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor).orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

	/**
	 * Executes a fire-and-forget task asynchronously on the DAO executor.
	 *
	 * @param task The runnable task to execute.
	 * @return A {@link CompletableFuture} that completes when the task finishes.
	 */
    protected CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor).orTimeout(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

}
