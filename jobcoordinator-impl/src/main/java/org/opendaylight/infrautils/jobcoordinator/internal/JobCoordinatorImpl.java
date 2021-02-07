package org.opendaylight.infrautils.jobcoordinator.internal;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.errorprone.annotations.Var;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.infrautils.jobcoordinator.JobCoordinator;
import org.opendaylight.infrautils.jobcoordinator.RollbackCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class JobCoordinatorImpl implements JobCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(JobCoordinatorImpl.class);
    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("fitsdnc-jobcoordinator-auditor");

    private static final long RETRY_WAIT_BASE_TIME_MILLIS = 1000;

    private static final int FJP_MAX_CAP = 0x7fff; // max #workers - 1; copy/pasted from ForkJoinPool private

    private static final AtomicInteger THREAD_INDEX = new AtomicInteger(0);
    private final ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
        ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        worker.setName("jobcoordinator-main-task-" + THREAD_INDEX.getAndIncrement());
        return worker;
    };
    private final ForkJoinPool fjPool = new ForkJoinPool(
        Math.min(FJP_MAX_CAP, Runtime.getRuntime().availableProcessors()), factory,
        LoggingThreadUncaughtExceptionHandler.toLogger(LOG), false);

    private final Map<String, JobQueue> jobQueueMap = new ConcurrentHashMap<>();
    private final ReentrantLock jobQueueMapLock = new ReentrantLock();
    private final Condition jobQueueMapCondition = jobQueueMapLock.newCondition();

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(5,
        get("jobcoordinator-onfailure-executor", 0, LOG));

    private final Thread jobQueueHandlerThread;
    private final AtomicBoolean jobQueueHandlerThreadStarted = new AtomicBoolean(false);

    @GuardedBy("jobQueueMapLock")
    private boolean isJobAvailable = false;

    private volatile boolean shutdown = false;

    private final ThreadCostAuditorHandler threadCostAuditor;

    private boolean systemIsHealthy = false;
    private final ScheduledFuture<?> cleanerTaskFuture;

    public JobCoordinatorImpl() {
        jobQueueHandlerThread = get("JobCoordinator-JobQueueHandler", 0, LOG)
            .newThread(new JobQueueHandler());
        cleanerTaskFuture =
            scheduledExecutorService.scheduleAtFixedRate(new JobQueueCleaner(), 0, 60, TimeUnit.SECONDS);
        this.threadCostAuditor = new ThreadCostAuditorHandler(AUDIT_LOGGER);
    }

    public ThreadFactory get(String namePrefix, Integer priority, Logger logger) {
        ThreadFactoryBuilder guavaBuilder = (new ThreadFactoryBuilder()).setNameFormat(namePrefix + "-%d")
            .setUncaughtExceptionHandler(LoggingThreadUncaughtExceptionHandler.toLogger(logger))
            .setDaemon(true);
        Objects.requireNonNull(guavaBuilder);
        Optional.of(priority).ifPresent(guavaBuilder::setPriority);
        logger.info("ThreadFactory created: {}", namePrefix);
        return guavaBuilder.build();
    }

    public void init() {
    }

    @SuppressWarnings("IllegalCatch")
    public void destroy() {
        LOG.info("JobCoordinator shutting down... (tasks still running may be stopped/cancelled/interrupted)");
        jobQueueMapLock.lock();
        try {
            shutdown = true;
            jobQueueMapCondition.signalAll();
        } finally {
            jobQueueMapLock.unlock();
        }

        fjPool.shutdownNow();
        scheduledExecutorService.shutdownNow();

        try {
            jobQueueHandlerThread.join(10000);
        } catch (InterruptedException e) {
            // Shouldn't get interrupted - either way we don't care.
        }
        if (!cleanerTaskFuture.isCancelled()) {
            cleanerTaskFuture.cancel(false);
        }

        LOG.info("JobCoordinator now closed for business.");
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker) {
        enqueueJob(key, mainWorker, null, JobCoordinator.DEFAULT_MAX_RETRIES);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker,
                           RollbackCallable rollbackWorker) {
        enqueueJob(key, mainWorker, rollbackWorker, JobCoordinator.DEFAULT_MAX_RETRIES);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker, int maxRetries) {
        enqueueJob(key, mainWorker, null, maxRetries);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker,
                           RollbackCallable rollbackWorker, int maxRetries) {

        jobQueueMapLock.lock();
        try {
            JobQueue jobQueue = jobQueueMap.computeIfAbsent(key, mapKey -> new JobQueue());
            JobEntry jobEntry = new JobEntry(key, jobQueue.getQueueId(), mainWorker, rollbackWorker, maxRetries);
            jobQueue.addEntry(jobEntry);
            LOG.trace("Added a job with key {}, job {} to the queue {}",
                key, jobEntry.getId(), jobQueue.getQueueId());
        } finally {
            jobQueueMapLock.unlock();
        }

        signalForNextJob();
    }

    private class JobQueueCleaner implements Runnable {

        private final Map<String, Short> jobQueueCheckCounters = new ConcurrentHashMap<>();

        @SuppressFBWarnings("RCN_REDUNDANT_COMPARISON_OF_NULL_AND_NONNULL_VALUE")
        private boolean notInUse(@Nullable JobQueue queue) {
            return null == queue || (null == queue.getExecutingEntry() && queue.isEmpty());
        }

        @Override
        @SuppressWarnings("Var")
        public void run() {
            Set<String> unusedJobQueues = new HashSet<>();
            jobQueueMap.forEach((jobKey, jobQueue) -> {
                if (notInUse(jobQueue)) {
                    jobQueueCheckCounters.compute(jobKey, (key, count) -> {
                        if (null == count) {
                            count = 0;
                        }
                        ++count;
                        // NOTE (clzhou)
                        // this is not accuracy enough because maybe all the jobs are completed
                        // just at the time we check; but this is ok because the queue will be re-created
                        // on new job received; we prefer such a method rather than adding and updating a
                        // timestamp at each operation on job queue
                        if (count > 3) {
                            unusedJobQueues.add(key);
                            return null;
                        } else {
                            return count;
                        }
                    });
                }
            });
            unusedJobQueues.forEach(jobKey -> jobQueueMap.compute(jobKey, (key, origin) -> {
                if (notInUse(origin)) {
                    return null;
                }
                return origin;
            }));
        }
    }

    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD")
    private void resumeTask() {
        jobQueueMapLock.lock();
        try {
            jobQueueMapCondition.signalAll();
        } finally {
            jobQueueMapLock.unlock();
        }
    }

    /**
     * Cleanup the submitted job from the job queue.
     **/
    private void clearJob(JobEntry jobEntry) {
        String jobKey = jobEntry.getKey();
        LOG.trace("About to clear job with key {}, job{} from queue {}",
            jobKey, jobEntry.getId(), jobEntry.getQueueId());
        JobQueue jobQueue = jobQueueMap.get(jobKey);
        if (jobQueue != null) {


            jobQueueMapLock.lock();
            try {
                if (jobQueue.isEmpty()) {
                    jobQueueMap.remove(jobKey);
                    LOG.trace("Removed jobQueue {} for jobKey {} while clearing job {}",
                        jobQueue.getQueueId(), jobKey, jobEntry.getId());
                }
            } finally {
                jobQueueMapLock.unlock();
            }
            jobEntry.setEndTime(System.currentTimeMillis());
            jobQueue.onJobFinished(jobEntry.getEndTime() - jobEntry.getStartTime());
            jobQueue.setExecutingEntry(null);

        } else {
            LOG.error("clearJob: jobQueueMap did not contain the key for this entry: {}", jobEntry);
        }
        signalForNextJob();
    }

    private void signalForNextJob() {
        if (jobQueueHandlerThreadStarted.compareAndSet(false, true)) {
            jobQueueHandlerThread.start();
        }

        jobQueueMapLock.lock();
        try {
            isJobAvailable = true;
            jobQueueMapCondition.signalAll();
        } finally {
            jobQueueMapLock.unlock();
        }
    }

    private boolean executeTask(Runnable task) {
        try {
            fjPool.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            if (!fjPool.isShutdown()) {
                LOG.error("ForkJoinPool task rejected", e);
            }

            return false;
        }
    }

    @SuppressFBWarnings(value = "UPM_UNCALLED_PRIVATE_METHOD",
        justification = "https://github.com/spotbugs/spotbugs/issues/811")
    private Future<?> scheduleTask(Runnable task, long delay, TimeUnit unit) {
        try {
            return scheduledExecutorService.schedule(task, delay, unit);
        } catch (RejectedExecutionException e) {
            if (!scheduledExecutorService.isShutdown()) {
                LOG.error("ScheduledExecutorService rejected task", e);
            }
            return immediateFailedFuture(e);
        }
    }

    @VisibleForTesting
    // Ideally this would be package-scoped but the unit test class is in a separate package.
    protected Thread getJobQueueHandlerThread() {
        return jobQueueHandlerThread;
    }

    /**
     * JobCallback class is used as a future callback for main and rollback
     * workers to handle success and failure.
     */
    private class JobCallback implements FutureCallback<List<Void>> {
        private final JobEntry jobEntry;

        JobCallback(JobEntry jobEntry) {
            this.jobEntry = jobEntry;
        }

        /**
         * This implies that all the future instances have returned success. --
         * TODO: Confirm this
         */
        @Override
        public void onSuccess(List<Void> voids) {
            LOG.trace("Job completed successfully with key {}, job {} from queue {}",
                jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId());
            if (LOG.isDebugEnabled()) {
                int retry = jobEntry.getMaxRetries() - jobEntry.getRetryCount();
                if (retry > 0) {
                    LOG.debug("job {} submit at {}ms ago by {} completed successful after retry {}, ",
                        jobEntry.getKey(), System.currentTimeMillis() - jobEntry.getStartedAtMilliSecond(),
                        jobEntry.getJobProviderClassName(),
                        retry);
                }
            }
            clearJob(jobEntry);
        }

        /**
         * This method is used to handle failure callbacks. If more retry
         * needed, the retrycount is decremented and mainworker is executed
         * again. After retries completed, rollbackworker is executed. If
         * rollbackworker fails, this is a double-fault. Double fault is logged
         * and ignored.
         */
        @Override
        public void onFailure(Throwable throwable) {
            int retryCount = jobEntry.decrementRetryCountAndGet();

            if (retryCount == 0 && jobEntry.getMaxRetries() > 0) {
                LOG.error("Job {} still failed on final retry: {}", jobEntry, throwable.getMessage());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("caused by:", throwable);
                }
            } else if (retryCount == 0 && jobEntry.getMaxRetries() == 0) {
                LOG.error("Job {} failed, no retries: {}", jobEntry, throwable.getMessage());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("caused by:", throwable);
                }
            } else {
                // If retryCount > 0, then the log should not be polluted with confusing WARN messages (because we're
                // about to retry it again, shortly; if it ultimately still fails, there will be a WARN); so DEBUG.
                LOG.debug("Job failed, will retry: {}", jobEntry, throwable);
            }
            if (jobEntry.getMainWorker() == null) {
                LOG.error("Job failed with Double-Fault. Bailing Out: {}", jobEntry);
                clearJob(jobEntry);
                return;
            }

            if (retryCount > 0) {
                long waitTime = RETRY_WAIT_BASE_TIME_MILLIS / retryCount;
                Futures.addCallback(JdkFutureAdapters.listenInPoolThread(scheduleTask(() -> {
                    MainTask worker = new MainTask(jobEntry);
                    executeTask(worker);
                }, waitTime, TimeUnit.MILLISECONDS)), new FutureCallback<Object>() {
                    @Override
                    public void onFailure(Throwable throwable) {
                        LOG.error("Retry of job failed; rolling back or clearing job: {}", jobEntry, throwable);
                        rollbackOrClear(jobEntry);
                    }

                    @Override
                    public void onSuccess(Object result) {
                        LOG.debug("Retry of job submission succeeded with key {}, job {} from queue {}",
                            jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId());
                    }
                }, MoreExecutors.directExecutor());
            } else {
                rollbackOrClear(jobEntry);
            }
        }
    }

    @SuppressFBWarnings(value = "UPM_UNCALLED_PRIVATE_METHOD",
        justification = "https://github.com/spotbugs/spotbugs/issues/811")
    private void rollbackOrClear(JobEntry jobEntry) {
        if (jobEntry.getRollbackWorker() != null) {
            jobEntry.setMainWorker(null);
            RollbackTask rollbackTask = new RollbackTask(jobEntry);
            executeTask(rollbackTask);
            return;
        }
        clearJob(jobEntry);
    }

    Map<String, JobQueue> jobQueueMap() {
        return Collections.unmodifiableMap(jobQueueMap);
    }

    ForkJoinPool executorPool() {
        return fjPool;
    }

    /**
     * RollbackTask is used to execute the RollbackCallable provided by the
     * application in the eventuality of a failure.
     */
    private class RollbackTask implements Runnable {
        private final JobEntry jobEntry;

        RollbackTask(JobEntry jobEntry) {
            this.jobEntry = jobEntry;
        }

        @Override
        @SuppressWarnings("checkstyle:IllegalCatch")
        public void run() {
            RollbackCallable rollbackWorker = jobEntry.getRollbackWorker();
            @Var List<ListenableFuture<Void>> futures = null;
            if (rollbackWorker != null) {
                try {
                    futures = rollbackWorker.apply(jobEntry.getFutures());
                } catch (Throwable e) {
                    LOG.error("Exception when executing job's rollbackWorker: {}", jobEntry, e);
                }
            } else {
                LOG.error("Unexpected no (null) rollback worker on job: {}", jobEntry);
            }

            if (futures == null || futures.isEmpty()) {
                LOG.trace("From RollbackTask: futures is null or empty. Clearing the jobQueue with key {},"
                    + " job {} from queue {}", jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId());
                clearJob(jobEntry);
                return;
            }

            jobEntry.setFutures(futures);
            ListenableFuture<List<Void>> listenableFuture = MoreGuavaFutures.allAsList(futures);
            Futures.addCallback(listenableFuture, new JobCallback(jobEntry), MoreExecutors.directExecutor());
        }
    }

    /**
     * Execute the MainWorker callable.
     */
    private class MainTask implements Runnable {
        private static final int LONG_JOBS_THRESHOLD_MS = 1000;
        private final JobEntry jobEntry;

        MainTask(JobEntry jobEntry) {
            this.jobEntry = jobEntry;
        }

        @Override
        @SuppressWarnings("checkstyle:illegalcatch")
        public void run() {
            @Var List<ListenableFuture<Void>> futures = null;
            long jobStartTimestampNanos = System.nanoTime();
            jobEntry.setStartTime(System.currentTimeMillis());
            LOG.trace("Running job with key {}, job {} from queue {}",
                jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId());

            try {
                Callable<List<ListenableFuture<Void>>> mainWorker = jobEntry.getMainWorker();
                if (mainWorker != null) {
                    futures = threadCostAuditor.runWithThreadCpuTimeCalculation(
                        mainWorker,
                        () -> {
                            MDC.put("job", jobEntry.getKey());
                            MDC.put("provider", jobEntry.getJobProviderClassName());
                        });
                } else {
                    LOG.error("Unexpected no (null) main worker on job: {}", jobEntry);
                }
                long jobExecutionTimeNanos = System.nanoTime() - jobStartTimestampNanos;
                printJobs(TimeUnit.NANOSECONDS.toMillis(jobExecutionTimeNanos));
            } catch (Throwable e) {
                LOG.error("Direct Exception (not failed Future) when executing job, won't even retry: {}", jobEntry, e);
            }

            if (futures == null || futures.isEmpty()) {
                LOG.trace("From MainTask: futures is null or empty. Clearing the jobQueue with key {},"
                    + " job {} from queue {}", jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId());
                clearJob(jobEntry);
                return;
            }

            List<ListenableFuture<Void>> nonNullFutures = futures.stream().filter(Objects::nonNull)
                .collect(Collectors.toList());
            if (nonNullFutures.isEmpty()) {
                LOG.trace("From MainTask nonNullFutures: Clearing the jobQueue with key {}, job {} from queue {}",
                    jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId());
                clearJob(jobEntry);
                return;
            }

            jobEntry.setFutures(futures);
            ListenableFuture<List<Void>> listenableFuture = MoreGuavaFutures.allAsList(futures);
            Futures.addCallback(listenableFuture, new JobCallback(jobEntry), MoreExecutors.directExecutor());
        }

        private void printJobs(long jobExecutionTime) {
            if (jobExecutionTime > LONG_JOBS_THRESHOLD_MS) {
                LOG.warn("Job with key {}, job {} from queue {} took {}ms to complete",
                    jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId(), jobExecutionTime);
                return;
            }
            LOG.trace("Job with key {}, job {} from queue {} took {}ms to complete",
                jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId(), jobExecutionTime);
        }
    }

    private class JobQueueHandler implements Runnable {
        @Override
        @SuppressWarnings("checkstyle:illegalcatch")
        public void run() {
            LOG.info("Starting JobQueue Handler Thread");
            while (true) {
                try {
                    for (Map.Entry<String, JobQueue> entry : jobQueueMap.entrySet()) {
                        if (shutdown) {
                            break;
                        }

                        JobQueue jobQueue = entry.getValue();
                        if (jobQueue.getExecutingEntry() != null) {
                            continue;
                        }
                        JobEntry jobEntry = jobQueue.poll();
                        if (jobEntry == null) {
                            // job queue is empty. so continue with next job queue entry
                            continue;
                        }
                        jobQueue.setExecutingEntry(jobEntry);
                        MainTask worker = new MainTask(jobEntry);
                        LOG.trace("Executing job with key {}, job {} from queue {}",
                            jobEntry.getKey(), jobEntry.getId(), jobEntry.getQueueId());

                        if (executeTask(worker)) {
                        }
                    }

                    if (!waitForJobIfNeeded()) {
                        break;
                    }
                } catch (Throwable e) {
                    LOG.error("Exception while executing the tasks", e);
                }
            }
        }

        // Suppress "Exceptional return value of java.util.concurrent.locks.Condition.await" - we really don't care
        // if the Condition was signaled or timed out as we use isJobAvailable to break or continue waiting.
        @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
        private boolean waitForJobIfNeeded() throws InterruptedException {
            jobQueueMapLock.lock();
            try {
                while (!systemIsHealthy || !isJobAvailable && !shutdown) {
                    jobQueueMapCondition.await(1, TimeUnit.SECONDS);
                }
                isJobAvailable = false;
                return !shutdown;
            } finally {
                jobQueueMapLock.unlock();
            }
        }
    }


    public Map<String, JobQueue> getJobQueueMap() {
        return jobQueueMap;
    }

    @Override
    public String toString() {
        boolean isJobAvailableFromLock;
        jobQueueMapLock.lock();
        try {
            isJobAvailableFromLock = isJobAvailable;
        } finally {
            jobQueueMapLock.unlock();
        }

        return MoreObjects.toStringHelper(this)
            .add("fjPool", fjPool)
            .add("jobQueueMap", jobQueueMap)
            .add("jobQueueMapLock", jobQueueMapLock)
            .add("scheduledExecutorService", scheduledExecutorService)
            .add("isJobAvailable", isJobAvailableFromLock)
            .add("jobQueueMapCondition", jobQueueMapCondition)
            .toString();
    }
}
