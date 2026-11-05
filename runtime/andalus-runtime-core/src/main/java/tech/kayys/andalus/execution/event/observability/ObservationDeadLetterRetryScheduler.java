package tech.kayys.andalus.execution.event.observability;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Schedules bounded, asynchronous retries for dead-letter entries.
 *
 * <p>The scheduler never sleeps on the caller's thread. A semaphore bounds
 * scheduled plus currently executing retry tasks because ScheduledExecutorService
 * implementations commonly use an unbounded delay queue. The supplied handler
 * is responsible for replay/re-enqueue semantics; this class does not remove
 * entries from a DLQ or provide durable retry state.</p>
 *
 * <p>Closing this component cancels queued work via shutdownNow. In-flight
 * handlers may be interrupted and must therefore be idempotent and interruption
 * aware. A process crash can still lose scheduled work.</p>
 */
public final class ObservationDeadLetterRetryScheduler implements AutoCloseable {
    private final ObservationDeadLetterRetryPolicy policy;
    private final ScheduledExecutorService executor;
    private final Semaphore capacity;
    private final Consumer<RetryFailure> failureListener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder scheduled = new LongAdder();
    private final LongAdder notRetryable = new LongAdder();
    private final LongAdder capacityRejected = new LongAdder();
    private final LongAdder closedOutcomes = new LongAdder();
    private final LongAdder handlerFailures = new LongAdder();

    /**
     * Creates a scheduler with a dedicated single-thread executor.
     *
     * @param policy retry policy
     * @param maxPendingRetries maximum scheduled plus in-flight retries
     * @param threadName dedicated worker thread name
     * @param failureListener receives handler failures; listener failures are isolated
     */
    public ObservationDeadLetterRetryScheduler(
            ObservationDeadLetterRetryPolicy policy,
            int maxPendingRetries,
            String threadName,
            Consumer<RetryFailure> failureListener) {
        this(policy, maxPendingRetries, new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable,
                    Objects.requireNonNull(threadName, "threadName must not be null"));
            thread.setDaemon(true);
            return thread;
        }), failureListener);
    }

    /**
     * Creates a scheduler around an externally configured executor.
     * The executor is owned by this scheduler and is shut down by {@link #close()}.
     */
    public ObservationDeadLetterRetryScheduler(
            ObservationDeadLetterRetryPolicy policy,
            int maxPendingRetries,
            ScheduledExecutorService executor,
            Consumer<RetryFailure> failureListener) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        if (maxPendingRetries < 1) {
            throw new IllegalArgumentException("maxPendingRetries must be at least one");
        }
        this.capacity = new Semaphore(maxPendingRetries);
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener must not be null");
        if (executor instanceof ScheduledThreadPoolExecutor scheduled) {
            scheduled.setRemoveOnCancelPolicy(true);
        }
    }

    /**
     * Schedules one retry. Capacity rejection leaves ownership of the entry with
     * the caller, which must retain or re-enqueue it.
     */
    public synchronized ScheduleResult schedule(
            ObservationDeadLetter deadLetter,
            int retryNumber,
            ObservationDeadLetterHandler handler) {
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        ObservationDeadLetterRetryPolicy.Decision decision = policy.decide(deadLetter, retryNumber);
        if (!decision.retry()) {
            notRetryable.increment();
            return new ScheduleResult(ScheduleStatus.NOT_RETRYABLE, decision);
        }
        if (closed.get()) {
            closedOutcomes.increment();
            return new ScheduleResult(ScheduleStatus.CLOSED, decision);
        }
        if (!capacity.tryAcquire()) {
            capacityRejected.increment();
            return new ScheduleResult(ScheduleStatus.CAPACITY_REJECTED, decision);
        }

        try {
            executor.schedule(() -> execute(deadLetter, decision, handler),
                    decision.delay().toNanos(), TimeUnit.NANOSECONDS);
            scheduled.increment();
            return new ScheduleResult(ScheduleStatus.SCHEDULED, decision);
        } catch (RejectedExecutionException rejected) {
            capacity.release();
            if (closed.get()) closedOutcomes.increment(); else capacityRejected.increment();
            return new ScheduleResult(
                    closed.get() ? ScheduleStatus.CLOSED : ScheduleStatus.CAPACITY_REJECTED,
                    decision);
        } catch (RuntimeException failure) {
            capacity.release();
            throw failure;
        }
    }

    private void execute(
            ObservationDeadLetter deadLetter,
            ObservationDeadLetterRetryPolicy.Decision decision,
            ObservationDeadLetterHandler handler) {
        try {
            handler.handle(deadLetter);
        } catch (Exception failure) {
            handlerFailures.increment();
            try {
                failureListener.accept(new RetryFailure(deadLetter, decision.retryNumber(), failure));
            } catch (RuntimeException ignored) {
                // A monitoring callback must not kill the scheduler worker.
            }
        } finally {
            capacity.release();
        }
    }

    public int availableCapacity() {
        return capacity.availablePermits();
    }

    /** Snapshot of scheduler outcome counters; values are weakly consistent under concurrency. */
    public Metrics metrics() {
        return new Metrics(scheduled.sum(), notRetryable.sum(), capacityRejected.sum(), closedOutcomes.sum(), handlerFailures.sum());
    }

    public record Metrics(long scheduled, long notRetryable, long capacityRejected, long closed, long handlerFailures) { }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    public enum ScheduleStatus {
        SCHEDULED,
        NOT_RETRYABLE,
        CAPACITY_REJECTED,
        CLOSED
    }

    public record ScheduleResult(
            ScheduleStatus status,
            ObservationDeadLetterRetryPolicy.Decision decision) {
        public ScheduleResult {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
        }
    }

    public record RetryFailure(
            ObservationDeadLetter deadLetter,
            int retryNumber,
            Exception cause) {
        public RetryFailure {
            Objects.requireNonNull(deadLetter, "deadLetter must not be null");
            if (retryNumber < 1) throw new IllegalArgumentException("retryNumber must be at least one");
            Objects.requireNonNull(cause, "cause must not be null");
        }
    }
}
