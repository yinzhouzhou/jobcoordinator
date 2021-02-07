package org.opendaylight.infrautils.jobcoordinator.internal;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.service.command.CommandSession;
import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.support.table.ShellTable;
import org.eclipse.jdt.annotation.Nullable;

@Command(scope = "infrautils", name = "list-jobs", description = "show all registered job coordinator jobs.")
public class ShowAllJobs implements Action {

    private final JobCoordinatorImpl jobCoordinator;

    public ShowAllJobs(JobCoordinatorImpl jobCoordinator) {
        this.jobCoordinator = jobCoordinator;
    }

    @Override
    @Nullable
    @SuppressWarnings("RegexpSinglelineJava")
    public Object execute(CommandSession session) throws Exception {
        Map<String, JobQueue> jobs = jobCoordinator.jobQueueMap();
        if (jobs.isEmpty()) {
            System.out.println("- no jobs in queue now.");
        } else {
            ShellTable table = new ShellTable().forceAscii();
            table.column("job key");
            table.column("in queue");
            table.column("executing job id");
            table.column("started at");
            table.column("retries");
            table.column("futures count");
            jobs.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingInt(JobQueue::jobCount).reversed()))
                .forEach(entry -> {
                    String key = entry.getKey();
                    JobQueue queue = entry.getValue();
                    table.addRow().addContent(
                        key,
                        queue.jobCount(),
                        Optional.ofNullable(queue.getExecutingEntry()).map(Objects::hash)
                            .map(Integer::toHexString).orElse("-"),
                        Optional.ofNullable(queue.getExecutingEntry()).map(JobEntry::getStartedAtMilliSecond)
                            .map(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S Z")::format)
                            .orElse("-"),
                        String.format("%s/%s",
                            Optional.ofNullable(queue.getExecutingEntry()).map(JobEntry::getRetryCount)
                                .flatMap(retry -> Optional.ofNullable(queue.getExecutingEntry())
                                    .map(JobEntry::getMaxRetries)
                                    .map(maxRetries -> maxRetries - retry))
                                .map(String::valueOf).orElse("-"),
                            Optional.ofNullable(queue.getExecutingEntry()).map(JobEntry::getMaxRetries)
                                .map(String::valueOf).orElse("-")),
                        Optional.ofNullable(queue.getExecutingEntry()).map(JobEntry::getFutures)
                            .map(List::size).map(String::valueOf).orElse("-")
                    );
                });
            table.print(System.out);
        }
        return null;
    }
}

