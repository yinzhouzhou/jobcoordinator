package org.opendaylight.infrautils.jobcoordinator.internal;

import java.util.concurrent.ForkJoinPool;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.service.command.CommandSession;
import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.support.table.ShellTable;
import org.eclipse.jdt.annotation.Nullable;

@Command(scope = "infrautils", name = "show-job-fjpool", description = "show the stats of job executor service.")
public class ShowJobExecutorPool implements Action {

    private final JobCoordinatorImpl jobCoordinator;

    public ShowJobExecutorPool(JobCoordinatorImpl jobCoordinator) {
        this.jobCoordinator = jobCoordinator;
    }

    @Override
    @Nullable
    @SuppressWarnings("RegexpSinglelineJava")
    public Object execute(CommandSession session) throws Exception {
        ForkJoinPool pool = jobCoordinator.executorPool();
        ShellTable table = new ShellTable().forceAscii();
        table.column("item");
        table.column("value");
        table.addRow().addContent("Thread Active", pool.getActiveThreadCount());
        table.addRow().addContent("Thread Running", pool.getRunningThreadCount());
        table.addRow().addContent("Thread Exist", pool.getPoolSize());
        table.addRow().addContent("Async Mode", pool.getAsyncMode());
        table.addRow().addContent("Parallelism", pool.getParallelism());
        table.addRow().addContent("Idle", pool.isQuiescent());
        table.addRow().addContent("Task Submitted", pool.getQueuedSubmissionCount());
        table.addRow().addContent("Task Queued", pool.getQueuedTaskCount());
        table.addRow().addContent("Task Stealed", pool.getStealCount());
        table.addRow().addContent("Task Stealed", pool.getStealCount());
        table.print(System.out);
        return null;
    }
}
