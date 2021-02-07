package org.opendaylight.infrautils.jobcoordinator;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.function.Function;

/**
 * A callable which runs in case a job task fails. It consumes the futures that were returned by the last failing
 * job, and returns the futures corresponding to the corrective transactions.
 */
public interface RollbackCallable extends Function<List<ListenableFuture<Void>>, List<ListenableFuture<Void>>> {
    /**
     * Roll back the transaction which led to the provided failed futures (futures resulting from the failed operation
     * &mdash; the futures themselves aren't necessarily failed).
     *
     * @param failedFutures The futures from the failed job.
     * @return The corrective roll back's resulting futures.
     */
    @Override
    List<ListenableFuture<Void>> apply(List<ListenableFuture<Void>> failedFutures);
}
