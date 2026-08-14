package cc.chlchun.kucolorfulname.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerOperationQueueTest {
    @Test
    void serializesOperationsForSamePlayer() throws Exception {
        PlayerOperationQueue queue = new PlayerOperationQueue();
        UUID playerId = UUID.randomUUID();
        CompletableFuture<String> firstDatabaseCall = new CompletableFuture<>();
        List<String> starts = new ArrayList<>();

        CompletableFuture<String> first = queue.submit(playerId, () -> {
            starts.add("first");
            return firstDatabaseCall;
        }).toCompletableFuture();
        CompletableFuture<String> second = queue.submit(playerId, () -> {
            starts.add("second");
            return CompletableFuture.completedFuture("second-result");
        }).toCompletableFuture();

        assertEquals(List.of("first"), starts);
        firstDatabaseCall.complete("first-result");
        assertEquals("first-result", first.get(1, TimeUnit.SECONDS));
        assertEquals("second-result", second.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("first", "second"), starts);
        assertEquals(0, queue.pendingPlayers());
    }

    @Test
    void closeRejectsQueuedAndFutureOperations() throws Exception {
        PlayerOperationQueue queue = new PlayerOperationQueue();
        UUID playerId = UUID.randomUUID();
        CompletableFuture<Void> runningDatabaseCall = new CompletableFuture<>();
        CompletableFuture<Void> first = queue.submit(
                playerId, () -> runningDatabaseCall).toCompletableFuture();
        CompletableFuture<String> queued = queue.submit(
                playerId, () -> CompletableFuture.completedFuture("unexpected")).toCompletableFuture();

        queue.close();
        runningDatabaseCall.complete(null);
        first.get(1, TimeUnit.SECONDS);
        assertClosedFailure(queued);
        assertClosedFailure(queue.submit(
                UUID.randomUUID(), () -> CompletableFuture.completedFuture("unexpected"))
                .toCompletableFuture());
    }

    private void assertClosedFailure(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(
                ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
}
