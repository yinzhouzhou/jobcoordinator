package org.opendaylight.infrautils.jobcoordinator.internal;

import com.google.errorprone.annotations.Var;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.eclipse.jdt.annotation.NonNull;
import org.slf4j.Logger;

public final class ThreadCostAuditorHandler {

    private static final long TOTAL_TIME_LOGGING_THRESHOLD_MILLISECOND = 1000L;
    private static final long WAITED_TIME_LOGGING_THRESHOLD_MILLISECOND = 100L;
    private static final long BLOCKED_TIME_LOGGING_THRESHOLD_MILLISECOND = 100L;

    private final Logger auditorLogger;

    public ThreadCostAuditorHandler(Logger auditorLogger) {
        this.auditorLogger = auditorLogger;
        ensureJmxMonitoring();
    }

    private static void ensureJmxMonitoring() {
        ThreadMXBean threadMxbean = ManagementFactory.getThreadMXBean();
        if (!threadMxbean.isThreadCpuTimeEnabled()) {
            if (threadMxbean.isThreadCpuTimeSupported()) {
                threadMxbean.setThreadCpuTimeEnabled(true);
            }
        }
        if (!threadMxbean.isThreadContentionMonitoringEnabled()) {
            if (threadMxbean.isThreadContentionMonitoringSupported()) {
                threadMxbean.setThreadContentionMonitoringEnabled(true);
            }
        }
    }

    private static String timeValueToPropertyValueString(double timeInMs) {
        // 统一用秒单位，单位不同时，只是在少量数据时人可读性好，但不利于大量日志的筛选
        return String.format("%dms", (long) timeInMs);
    }

    public <R> R runWithThreadCpuTimeCalculation(@NonNull Callable<R> cmd, Runnable mdcSetter) throws Exception {
        Function<ThreadInfo, Long> waitedTimeFunc = info -> null == info ? -1 : info.getWaitedTime();
        Function<ThreadInfo, Long> blockedTimeFunc = info -> null == info ? -1 : info.getBlockedTime();
        BiFunction<Long, Long, Long> diffCalculator = (before, after) -> {
            if (before < 0 || after < 0 || after < before) {
                return -1L;
            }
            return after - before;
        };
        ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
        long pid = Thread.currentThread().getId();
        long before = System.currentTimeMillis();
        @Var ThreadInfo tinfo = threadMxBean.getThreadInfo(pid);
        long beforeThreadCpuTime = threadMxBean.getCurrentThreadCpuTime();
        long beforeThreadWaitedTime = waitedTimeFunc.apply(tinfo);
        long beforeThreadBlockedTime = blockedTimeFunc.apply(tinfo);
        try {
            return cmd.call();
        } finally {
            @Var long after = System.currentTimeMillis();
            if (after < before) {
                after = before + 1;
            }
            long totalTimeMs = after - before;
            long afterThreadCpuTime = threadMxBean.getCurrentThreadCpuTime();
            tinfo = threadMxBean.getThreadInfo(pid);
            long afterThreadWaitedTime = waitedTimeFunc.apply(tinfo);
            long afterThreadBlockedTime = blockedTimeFunc.apply(tinfo);
            long cpuTimeMs =
                (long) nanosecondToMillisecond(diffCalculator.apply(beforeThreadCpuTime, afterThreadCpuTime));
            long waitedTimeMs = diffCalculator.apply(beforeThreadWaitedTime, afterThreadWaitedTime);
            long blockedTimeMs = diffCalculator.apply(beforeThreadBlockedTime, afterThreadBlockedTime);
            if (totalTimeMs >= TOTAL_TIME_LOGGING_THRESHOLD_MILLISECOND
                || waitedTimeMs > WAITED_TIME_LOGGING_THRESHOLD_MILLISECOND
                || blockedTimeMs > BLOCKED_TIME_LOGGING_THRESHOLD_MILLISECOND) {
                mdcSetter.run();
                auditorLogger.info("total: {}, cputime: {}, waited: {}, blocked: {}",
                    timeValueToPropertyValueString(totalTimeMs),
                    timeValueToPropertyValueString(cpuTimeMs),
                    timeValueToPropertyValueString(waitedTimeMs),
                    timeValueToPropertyValueString(blockedTimeMs));
            }
        }
    }

    private static double nanosecondToMillisecond(long nanosecond) {
        return (double) nanosecond / 1000_000;
    }

}
