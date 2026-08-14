package cc.chlchun.kucolorfulname.storage;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryExecutorTest {
    @Test
    void completesSubmittedCallable() throws Exception {
        try (RepositoryExecutor executor = executor(1, Duration.ofSeconds(1))) {
            CompletableFuture<String> future = executor.submit(() -> "stored");

            assertEquals("stored", future.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void returnsExceptionalFutureWhenQueueIsFull() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (RepositoryExecutor executor = executor(1, Duration.ofSeconds(1))) {
            CompletableFuture<Void> first = executor.submit(() -> {
                running.countDown();
                release.await();
                return null;
            });
            assertTrue(running.await(1, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = executor.submit(() -> 2);

            CompletableFuture<Integer> rejected = assertDoesNotThrow(() -> executor.submit(() -> 3));
            assertFailure(rejected, RejectedExecutionException.class);

            release.countDown();
            first.get(1, TimeUnit.SECONDS);
            assertEquals(2, queued.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void forceShutdownRejectsQueuedTasksAndFutureSubmissions() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        RepositoryExecutor executor = executor(1, Duration.ofMillis(25));
        try {
            CompletableFuture<Void> active = executor.submit(() -> {
                running.countDown();
                release.await();
                return null;
            });
            assertTrue(running.await(1, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = executor.submit(() -> {
                queuedRan.set(true);
                return 2;
            });

            CompletableFuture.runAsync(executor::close).get(1, TimeUnit.SECONDS);

            assertFailure(active, InterruptedException.class);
            assertFailure(queued, RejectedExecutionException.class);
            assertFalse(queuedRan.get());
            CompletableFuture<Integer> afterClose = assertDoesNotThrow(() -> executor.submit(() -> 3));
            assertFailure(afterClose, RejectedExecutionException.class);
            assertDoesNotThrow(executor::close);
        } finally {
            release.countDown();
            executor.close();
        }
    }

    private RepositoryExecutor executor(int queueCapacity, Duration shutdownTimeout) {
        return new RepositoryExecutor(
                1,
                queueCapacity,
                Thread.ofPlatform().daemon(true).name("repository-executor-test").factory(),
                shutdownTimeout
        );
    }

    private <T extends Throwable> void assertFailure(
            CompletableFuture<?> future,
            Class<T> expectedType
    ) throws Exception {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS)
        );
        assertInstanceOf(expectedType, failure.getCause());
    }
}
