package cc.chlchun.kucolorfulname.platform;

/**
 * A server-agnostic handle for a scheduled Paper or Folia task.
 */
public interface TaskHandle {
    /**
     * Attempts to prevent the task from running again.
     *
     * @return {@code true} when this call cancelled pending work
     */
    boolean cancel();

    /**
     * @return whether the task was cancelled
     */
    boolean isCancelled();

    /**
     * @return whether a one-shot task finished, was rejected, or was cancelled
     */
    boolean isDone();
}
