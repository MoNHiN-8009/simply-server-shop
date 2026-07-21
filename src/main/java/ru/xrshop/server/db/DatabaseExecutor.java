package ru.xrshop.server.db;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class DatabaseExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;

    public DatabaseExecutor(int queueSize) {
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(16, queueSize)), runnable -> {
            Thread thread = new Thread(runnable, "XRShop-SQLite");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> submit(CheckedSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try { future.complete(supplier.get()); }
                catch (Throwable throwable) { future.completeExceptionally(throwable); }
            });
        } catch (RejectedExecutionException ex) { future.completeExceptionally(ex); }
        return future;
    }

    public CompletableFuture<Void> run(CheckedRunnable runnable) {
        return submit(() -> { runnable.run(); return null; });
    }

    @Override public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
    }

    @FunctionalInterface public interface CheckedSupplier<T> { T get() throws Exception; }
    @FunctionalInterface public interface CheckedRunnable { void run() throws Exception; }
}
