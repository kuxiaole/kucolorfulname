package cc.chlchun.kucolorfulname.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class PlayerOperationQueue implements AutoCloseable {
    private final ConcurrentMap<UUID, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    <T> CompletionStage<T> submit(UUID playerId, Supplier<? extends CompletionStage<T>> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Colorful-name service is closed"));
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Void>> nextReference = new AtomicReference<>();
        tails.compute(playerId, (ignored, previous) -> {
            CompletableFuture<Void> start = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((value, failure) -> null);
            CompletableFuture<Void> next = start.thenCompose(unused -> execute(operation, result));
            nextReference.set(next);
            return next;
        });

        CompletableFuture<Void> next = nextReference.get();
        next.whenComplete((ignored, failure) -> tails.remove(playerId, next));
        return result;
    }

    int pendingPlayers() {
        return tails.size();
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private <T> CompletableFuture<Void> execute(
            Supplier<? extends CompletionStage<T>> operation,
            CompletableFuture<T> result
    ) {
        if (closed.get()) {
            result.completeExceptionally(new IllegalStateException("Colorful-name service is closed"));
            return CompletableFuture.completedFuture(null);
        }
        try {
            return operation.get().handle((value, failure) -> {
                if (failure == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(failure);
                }
                return (Void) null;
            }).toCompletableFuture();
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            return CompletableFuture.completedFuture(null);
        }
    }
}
