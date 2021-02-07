package org.opendaylight.infrautils.jobcoordinator.internal;

import com.google.common.base.Strings;
import java.text.SimpleDateFormat;
import java.util.Objects;
import java.util.Optional;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.service.command.CommandSession;
import org.apache.karaf.shell.commands.Argument;
import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.support.table.ShellTable;
import org.eclipse.jdt.annotation.Nullable;

@Command(scope = "infrautils", name = "show-job", description = "show details abount a registered job coordinator job.")
public class ShowJobDetails implements Action {

    @Argument(index = 0)
    private String jobKey = "";

    private final JobCoordinatorImpl jobCoordinator;

    @SuppressWarnings("FieldCanBeFinal")
    public ShowJobDetails(JobCoordinatorImpl jobCoordinator) {
        this.jobCoordinator = jobCoordinator;
    }

    public void setJobKey(String jobKey) {
        this.jobKey = jobKey;
    }

    @Override
    @Nullable
    @SuppressWarnings("RegexpSinglelineJava")
    public Object execute(CommandSession session) throws Exception {
        if (Strings.isNullOrEmpty(jobKey)) {
            System.out.println("- job key is needed.");
        } else {
            JobQueue queue = Optional.of(jobCoordinator.jobQueueMap()).map(map -> map.get(jobKey)).orElse(null);
            if (null == queue) {
                System.out.println(String.format("- no job with key %s is registered now.", jobKey));
            } else {
                ShellTable table = new ShellTable().forceAscii();
                table.column("item");
                table.column("content");
                table.addRow().addContent("job count", queue.jobCount());
                @Nullable JobEntry entry = queue.getExecutingEntry();
                table.addRow().addContent("executing job", Optional.ofNullable(entry).map(Objects::hash).orElse(null));
                if (null != entry) {
                    table.addRow().addContent(
                        "submit at",
                        Optional.of(entry).map(JobEntry::getStartedAtMilliSecond)
                            .map(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S Z")::format).orElse("-"));
                    table.addRow().addContent(
                        "retries",
                        String.format("%s/%s",
                            Optional.ofNullable(queue.getExecutingEntry()).map(JobEntry::getRetryCount)
                                .map(String::valueOf).orElse("-"),
                            Optional.ofNullable(queue.getExecutingEntry()).map(JobEntry::getMaxRetries)
                                .map(String::valueOf).orElse("-")));
                    StringBuilder futuresStr = new StringBuilder();
                    Optional.of(entry)
                        .map(JobEntry::getFutures)
                        .ifPresent(futures -> futures.forEach(future -> futuresStr.append(future).append("\n")));
                    if (futuresStr.length() > 0) {
                        futuresStr.delete(futuresStr.length() - 1, 1);
                    }
                    table.addRow().addContent("futures", futuresStr.toString());
                    table.addRow().addContent("main worker", entry.getMainWorker());
                    table.addRow().addContent("rollback worker", entry.getRollbackWorker());
                }
                table.print(System.out);
            }
        }
        return null;
    }
}
