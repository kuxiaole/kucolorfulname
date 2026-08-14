package cc.chlchun.kucolorfulname.storage;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded executor that keeps repository submissions asynchronous on overload and shutdown. */
final class RepositoryExecutor implements AutoCloseable {
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private final ThreadPoolExecutor executor;
    private final long shutdownTimeoutNanos;
    private final AtomicBoolean closed = new AtomicBoolean();

    RepositoryExecutor(int threadCount, ThreadFactory threadFactory) {
        this(threadCount, DEFAULT_QUEUE_CAPACITY, threadFactory, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    RepositoryExecutor(
            int threadCount,
            int queueCapacity,
            ThreadFactory threadFactory,
            Duration shutdownTimeout
    ) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must not be negative");
        }
        this.shutdownTimeoutNanos = shutdownTimeout.toNanos();
        this.executor = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Objects.requireNonNull(threadFactory, "threadFactory"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        RepositoryTask<T> repositoryTask = new RepositoryTask<>(task, future);
        if (closed.get()) {
            repositoryTask.reject(new RejectedExecutionException("Repository executor is closed"));
            return future;
        }
        try {
            executor.execute(repositoryTask);
        } catch (RejectedExecutionException exception) {
            repositoryTask.reject(exception);
        }
        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutNanos, TimeUnit.NANOSECONDS)) {
                rejectQueued(executor.shutdownNow());
            }
        } catch (InterruptedException exception) {
            rejectQueued(executor.shutdownNow());
            Thread.currentThread().interrupt();
        }
    }

    private static void rejectQueued(List<Runnable> queuedTasks) {
        RejectedExecutionException failure = new RejectedExecutionException(
                "Repository executor closed before task could start"
        );
        for (Runnable queuedTask : queuedTasks) {
            if (queuedTask instanceof RepositoryTask<?> repositoryTask) {
                repositoryTask.reject(failure);
            }
        }
    }

    private static final class RepositoryTask<T> implements Runnable {
        private final Callable<T> task;
        private final CompletableFuture<T> future;

        private RepositoryTask(Callable<T> task, CompletableFuture<T> future) {
            this.task = task;
            this.future = future;
        }

        @Override
        public void run() {
            if (future.isDone()) {
                return;
            }
            try {
                future.complete(task.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        }

        private void reject(RejectedExecutionException failure) {
            future.completeExceptionally(failure);
        }
    }
}
