package org.opendaylight.infrautils.jobcoordinator.internal;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.ListenableFuture;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.infrautils.jobcoordinator.RollbackCallable;

/**
 * JobEntry is the entity built per job submitted by the application and
 * enqueued to the book-keeping data structure.
 */
class JobEntry {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);
    private final String id = "J" + ID_GENERATOR.getAndIncrement();
    private final String key;
    private final String queueId;
    private volatile @Nullable Callable<List<ListenableFuture<Void>>> mainWorker;
    private final @Nullable RollbackCallable rollbackWorker;
    private final int maxRetries;
    private volatile int retryCount;
    private static final AtomicIntegerFieldUpdater<JobEntry> RETRY_COUNT_FIELD_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(JobEntry.class, "retryCount");
    private volatile @Nullable List<ListenableFuture<Void>> futures;
    private long startTime = -1;
    private long endTime = -1;
    private final long submittedAtNanoTime;
    private final long startedAt;
    private final String providerPkgName;
    private final String providerClassName;


    @SuppressFBWarnings(value = "NP_NONNULL_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
            justification = "TYPE_USE and SpotBugs")
    JobEntry(String key, String queueId, Callable<List<ListenableFuture<Void>>> mainWorker,
             @Nullable RollbackCallable rollbackWorker,
            int maxRetries) {
        this.key = key;
        this.queueId = queueId;
        this.mainWorker = mainWorker;
        this.rollbackWorker = rollbackWorker;
        this.maxRetries = maxRetries;
        this.retryCount = maxRetries;
        this.startedAt = System.currentTimeMillis();
        this.submittedAtNanoTime = System.nanoTime();
        this.providerPkgName = requireNonNull(mainWorker).getClass().getPackage().getName();
        this.providerClassName = requireNonNull(mainWorker).getClass().getSimpleName();
    }

    public long getSubmittedAtNanoTime() {
        return this.submittedAtNanoTime;
    }

    public long getStartedAtMilliSecond() {
        return this.startedAt;
    }

    public String getJobProviderPkgName() {
        return providerPkgName;
    }

    public String getJobProviderClassName() {
        return providerClassName;
    }

    /**
     * Get the key provided by the application that segregates the callables
     * that can be run parallely. NOTE: Currently, this is a string. Can be
     * converted to Object where Object implementation should provide the
     * hashcode and equals methods.
     */
    public String getKey() {
        return key;
    }

    public String getId() {
        return id;
    }

    public String getQueueId() {
        return queueId;
    }

    public @Nullable Callable<List<ListenableFuture<Void>>> getMainWorker() {
        return mainWorker;
    }

    public void setMainWorker(@Nullable Callable<List<ListenableFuture<Void>>> mainWorker) {
        this.mainWorker = mainWorker;
    }

    public @Nullable RollbackCallable getRollbackWorker() {
        return rollbackWorker;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int decrementRetryCountAndGet() {
        if (this.retryCount == 0) {
            return 0;
        }

        return RETRY_COUNT_FIELD_UPDATER.decrementAndGet(this);
    }

    public List<ListenableFuture<Void>> getFutures() {
        List<ListenableFuture<Void>> nullableFutures = futures;
        return nullableFutures != null ? nullableFutures : emptyList();
    }


    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        if (this.startTime < 0) {
            this.startTime = startTime;
        }
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public void setFutures(List<ListenableFuture<Void>> futures) {
        this.futures = futures;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("id", id)
            .add("key", key)
            .add("queueId", queueId)
            .add("mainWorker", mainWorker)
            .add("rollbackWorker", rollbackWorker)
            .add("maxRetries", maxRetries)
            .add("retryCount", retryCount)
            .add("futures", futures)
            .add("startTime", startTime)
            .add("endTime", endTime)
            .add("submittedAtNanoTime", submittedAtNanoTime)
            .add("startedAt", startedAt)
            .add("providerPkgName", providerPkgName)
            .add("providerClassName", providerClassName)
            .toString();
    }
}
