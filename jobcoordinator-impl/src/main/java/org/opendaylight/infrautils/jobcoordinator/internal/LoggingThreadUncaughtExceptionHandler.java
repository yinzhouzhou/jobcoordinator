package org.opendaylight.infrautils.jobcoordinator.internal;

import java.util.Objects;
import org.slf4j.Logger;


public class LoggingThreadUncaughtExceptionHandler  implements Thread.UncaughtExceptionHandler {
    private final Logger logger;

    public static Thread.UncaughtExceptionHandler toLogger(Logger logger) {
        return new LoggingThreadUncaughtExceptionHandler(logger);
    }

    private LoggingThreadUncaughtExceptionHandler(Logger logger) {
        this.logger = (Logger) Objects.requireNonNull(logger, "logger");
    }

    public void uncaughtException(Thread thread, Throwable throwable) {
        this.logger.error("Thread terminated due to uncaught exception: {}", thread.getName(), throwable);
    }
}