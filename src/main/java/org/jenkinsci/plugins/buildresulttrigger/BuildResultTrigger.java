package org.jenkinsci.plugins.buildresulttrigger;

import antlr.ANTLRException;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.model.*;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.util.SequentialExecutionQueue;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.jelly.XMLOutput;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.jenkinsci.lib.xtrigger.AbstractTriggerByFullContext;
import org.jenkinsci.lib.xtrigger.XTriggerDescriptor;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;
import org.jenkinsci.plugins.buildresulttrigger.model.BuildResultTriggerInfo;
import org.jenkinsci.plugins.buildresulttrigger.model.CheckedResult;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import jenkins.model.DependencyDeclarer;

/**
 * @author Gregory Boissinot
 */
public class BuildResultTrigger extends AbstractTriggerByFullContext<BuildResultTriggerContext> implements DependencyDeclarer {

    private final static long serialVersionUID = 1L;

    private boolean combinedJobs;

    private transient BuildResultTriggerInfo[] jobsInfo = new BuildResultTriggerInfo[0];

    @DataBoundConstructor
    public BuildResultTrigger(String cronTabSpec, boolean combinedJobs, BuildResultTriggerInfo[] jobsInfo) throws ANTLRException {
        super(cronTabSpec);
        this.combinedJobs = combinedJobs;
        this.jobsInfo = Arrays.copyOf(jobsInfo, jobsInfo.length);
    }

    public boolean isCombinedJobs() {
        return combinedJobs;
    }

    public BuildResultTriggerInfo[] getJobsInfo() {
        return Arrays.copyOf(jobsInfo, jobsInfo.length);
    }

    @Override
    public File getLogFile() {
        if (job == null) {
            return new File("buildResultTrigger-polling.log");
        }

        return new File(job.getRootDir(), "buildResultTrigger-polling.log");
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        BuildResultTriggerAction action = new InternalBuildResultTriggerAction(getDescriptor().getDisplayName());
        return Collections.singleton(action);
    }

    public void buildDependencyGraph(AbstractProject ap, DependencyGraph dg) {
        if (job instanceof AbstractProject) {
            for (BuildResultTriggerInfo info : jobsInfo) {
                for (String jobName : info.getJobNamesAsArray()) {
                    AbstractProject upstream = Jenkins.getActiveInstance().getItem(jobName, job, AbstractProject.class);
                    if (upstream != null) {
                        dg.addDependency(new DependencyGraph.Dependency(upstream, (AbstractProject) job) {
                            @Override
                            public boolean shouldTriggerBuild(AbstractBuild build, TaskListener listener, List<Action> actions) {
                                // Do not let BuildTrigger start the downstream build as a result; let BuildResultTrigger decide.
                                // If BuildResultTrigger were rewritten to not use polling, this method could actually do the status check instead.
                                return false;
                            }
                        });
                    }
                }
            }
        }
    }

    public final class InternalBuildResultTriggerAction extends BuildResultTriggerAction {

        private transient String actionTitle;

        public InternalBuildResultTriggerAction(String actionTitle) {
            this.actionTitle = actionTitle;
        }

        // class is final and method only used for Jelly so can change signature
        @SuppressWarnings("unused")
        public Job<?, ?> getOwner() {
            return (Job) job;
        }

        public String getIconFileName() {
            return "clipboard.gif";
        }

        public String getDisplayName() {
            return "BuildResultTrigger Log";
        }

        public String getUrlName() {
            return "buildResultTriggerPollLog";
        }

        @SuppressWarnings("unused")
        public String getLabel() {
            return actionTitle;
        }

        @SuppressWarnings("unused")
        public String getLog() throws IOException {
            return Util.loadFile(getLogFile());
        }

        @SuppressWarnings("unused")
        public void writeLogTo(XMLOutput out) throws IOException {
            new AnnotatedLargeText<InternalBuildResultTriggerAction>(getLogFile(), Charset.defaultCharset(), true, this).writeHtmlTo(0, out.asWriter());
        }
    }


    @Override
    protected boolean requiresWorkspaceForPolling() {
        return false;
    }

    @Override
    protected String getName() {
        return "BuildResultTrigger";
    }

    @Override
    protected Action[] getScheduledActions(Node node, XTriggerLog log) {
        return new Action[0];
    }

    @Override
    protected String getCause() {
        return "A change to build result";
    }

    @Override
    public boolean isContextOnStartupFetched() {
        return true;
    }

    @Override
    protected boolean requirePollingNode() {
        return false;
    }

    @Override
    protected BuildResultTriggerContext getContext(XTriggerLog log) throws XTriggerException {
        Map<String, Integer> contextResults = new HashMap<String, Integer>();
        SecurityContext securityContext = ACL.impersonate(ACL.SYSTEM);
        try {
            for (BuildResultTriggerInfo info : jobsInfo) {
                for (String jobName : info.getJobNamesAsArray()) {
                    Job job = Jenkins.getActiveInstance().getItem(jobName, this.job.getParent(), Job.class);

                    if (isValidBuildResultProject(job)) {
                        Run lastBuild = job.getLastCompletedBuild();
                        if (lastBuild != null) {
                            int buildNumber = lastBuild.getNumber();
                            if (buildNumber != 0) {
                                contextResults.put(jobName, buildNumber);
                            }
                        }
                    } else {
                        log.info(String.format("Job %s is not a valid job - ignoring it.", jobName));
                    }
                }
            }
        } finally {
            SecurityContextHolder.setContext(securityContext);
        }
        return new BuildResultTriggerContext(contextResults);
    }

    @Override
    protected BuildResultTriggerContext getContext(Node pollingNode, XTriggerLog log) throws XTriggerException {
        return getContext(log);
    }

    private boolean isValidBuildResultProject(Job item) {
        // TODO: check if could be Matrix Configuration
        return item != null;
    }

    @Override
    protected boolean checkIfModified(BuildResultTriggerContext oldContext,
                                      BuildResultTriggerContext newContext,
                                      XTriggerLog log)
            throws XTriggerException {

        oldContext = syncWithDisk(oldContext, newContext, log);

        SecurityContext securityContext = ACL.impersonate(ACL.SYSTEM);
        try {

            int nbCheckedJobs = 0;
            int nbModifiedJobs = 0;

            log.info(String.format("Found old results %s.", oldContext.getResults()));
            log.info(String.format("Found new results %s.", newContext.getResults()));

            for (BuildResultTriggerInfo info : jobsInfo) {
                CheckedResult[] expectedResults = info.getCheckedResults();
                for (String jobName : info.getJobNamesAsArray()) {

                    nbCheckedJobs++;

                    boolean modifiedJob = checkIfModifiedJob(jobName, expectedResults, oldContext, newContext, log);

                    //Stop at the first modification on the combination mode
                    if (!combinedJobs && modifiedJob) {
                        log.info(String.format("Job %s is modified. Triggering a new build.", jobName));
                        setNewContext(newContext);
                        return true;
                    }

                    //Stop if combined if activated and there isn't a modification
                    if (combinedJobs && !modifiedJob) {
                        log.info(String.format("Combination activated. Job %s has not changed. Waiting for next poll.", jobName));
                        resetOldContext(oldContext);
                        return false;
                    }

                    if (modifiedJob) {
                        nbModifiedJobs++;
                    }
                }
            }

            if (combinedJobs && nbCheckedJobs == nbModifiedJobs) {
                log.info("Combination activated and all jobs has changed. Triggering a new build.");
                setNewContext(newContext);
                return true;
            } else if (combinedJobs) {
                resetOldContext(oldContext);
                return false;
            } else {
                setNewContext(newContext);
                return false;
            }

        } finally {
            SecurityContextHolder.setContext(securityContext);
        }
    }

    private boolean checkIfModifiedJob(String jobName, CheckedResult[] expectedResults, BuildResultTriggerContext oldContext, BuildResultTriggerContext newContext,
                                       XTriggerLog log) {
        log.info(String.format("Checking changes for job %s.", jobName));

        final Map<String, Integer> oldContextResults = oldContext.getResults();
        final Map<String, Integer> newContextResults = newContext.getResults();

        if (newContextResults == null || newContextResults.size() == 0) {
            log.info(String.format("No new builds to check for the job %s", jobName));
            return false;
        }

        if (newContextResults.size() != oldContextResults.size()) {
            log.info(String.format("Different number of monitored jobs detected. Old size: %s, New size: %s", oldContextResults.size(), newContextResults.size()));
            return isMatchingExpectedResults(jobName, expectedResults, log, newContextResults.get(jobName));
        }

        Integer newLastBuildNumber = newContextResults.get(jobName);
        if (newLastBuildNumber == null || newLastBuildNumber == 0) {
            log.info("New last build number is null or 0");
            return false;
        }

        Integer oldLastBuildNumber = oldContextResults.get(jobName);
        if (oldLastBuildNumber == null || oldLastBuildNumber == 0) {
            log.info("Old last build number is null or 0");
            return isMatchingExpectedResults(jobName, expectedResults, log, newContextResults.get(jobName));
        }

        //Process if there is a new build between now and previous polling
        if (newLastBuildNumber.intValue() != oldLastBuildNumber.intValue()) {
            log.info(String.format("Difference in build numbers detected. Old build number: %s, New build number: %s", oldLastBuildNumber, newLastBuildNumber));
            return isMatchingExpectedResults(jobName, expectedResults, log, newContextResults.get(jobName));
        }

        log.info(String.format("There are no new builds for the job %s.", jobName));
        return false;
    }

    private BuildResultTriggerContext syncWithDisk(BuildResultTriggerContext oldContext,
                                                   BuildResultTriggerContext newContext, XTriggerLog log) {
        BuildResultTriggerContext diskContext = loadContext(log);
        saveContext(newContext, log);
        return mergeContexts(oldContext, diskContext);
    }

    //Merge disk and memory contexts, use memory build number unless disk build number is lower
    private BuildResultTriggerContext mergeContexts(BuildResultTriggerContext memoryContext,
                                                    BuildResultTriggerContext diskContext) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        Map<String, Integer> memoryMap = memoryContext.getResults();
        Map<String, Integer> diskMap = diskContext.getResults();

        for (Map.Entry<String, Integer> memoryEntry : memoryMap.entrySet()) {
            Integer memoryBuildNumber = Integer.valueOf(memoryEntry.getValue());
            Integer diskBuildNumber = diskMap.get(memoryEntry.getKey());
            if (diskBuildNumber != null && diskBuildNumber < memoryBuildNumber) {
                map.put(memoryEntry.getKey(), diskBuildNumber);
            } else {
                map.put(memoryEntry.getKey(), memoryBuildNumber);
            }
        }

        return new BuildResultTriggerContext(map);
    }

    private void saveContext(BuildResultTriggerContext context, XTriggerLog log) {
        if (context != null && context.getResults() != null && context.getResults().size() > 0) {
            File mapFile = getBuildsMapFile();
            if (mapFile != null) {
                ArrayList<String> lines = new ArrayList<String>();
                for (Map.Entry<String, Integer> entry : context.getResults().entrySet()) {
                    lines.add(String.format("%s,%s", entry.getKey(), entry.getValue()));
                }
                try {
                    FileUtils.writeLines(mapFile, lines);
                } catch (IOException e) {
                    if (log != null) {
                        log.error(String.format("Failed to write monitored build results to disk. File: %s", mapFile.getAbsolutePath()));
                        log.error(ExceptionUtils.getStackTrace(e));
                    }
                }
            } else {
                if (log != null) log.error("Could not save monitored build results to disk, unable to calculate job directory.");
            }
        }
    }

    private BuildResultTriggerContext loadContext(XTriggerLog log) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        File mapFile = getBuildsMapFile();
        if (mapFile != null) {
            if (mapFile.exists()) {
                List<String> lines = null;
                try {
                    lines = FileUtils.readLines(mapFile);
                } catch (IOException e) {
                    if (log != null) {
                        log.error(String.format("Failed to read monitored build results from disk. File: %s", mapFile.getAbsolutePath()));
                        log.error(ExceptionUtils.getStackTrace(e));
                    }
                }
                if (lines != null) {
                    for (String line : lines) {
                        String[] segments = line.split(",");
                        if (segments.length == 2) {
                            map.put(segments[0], Integer.valueOf(segments[1]));
                        } else {
                            if (log != null) {
                                log.error("Monitored build results file has invalid content:");
                                log.error(String.format("  File: %s", mapFile.getAbsolutePath()));
                                log.error(String.format("  Invalid line: \"%s\"", line));
                                log.error("Skipping line.");
                            }
                        }
                    }
                }
            }
        } else {
            log.error("Could not read monitored build results from disk, unable to calculate job directory.");
        }
        return new BuildResultTriggerContext(map);
    }

    private void renameJobInSavedContext(String oldName, String newName) {
        BuildResultTriggerContext context = loadContext(null);
        if (context != null && context.getResults() != null) {
            Map<String, Integer> results = context.getResults();
            if (results != null) {
                Integer value = results.get(oldName);
                if (value != null) {
                    results.remove(oldName);
                    results.put(newName, value);
                }
            }
            saveContext(new BuildResultTriggerContext(results), null);
        }
    }

    private File getBuildsMapFile() {
        return job != null && job.getRootDir() != null
                ? new File(job.getRootDir(), "buildResultTrigger-build-records.csv") : null;
    }

    private void logContext(String contextName, BuildResultTriggerContext context, XTriggerLog log) {
        log.info(String.format("Context name: %s", contextName));
        for (Map.Entry<String, Integer> entry : context.getResults().entrySet()) {
            log.info(String.format("  %s : %s", entry.getKey(), entry.getValue()));
        }
    }

    private boolean isMatchingExpectedResults(String jobName, CheckedResult[] expectedResults, XTriggerLog log,
                                              Integer buildId) {
        log.info(String.format("Checking expected job build results for the job %s.", jobName));

        if (expectedResults == null || expectedResults.length == 0) {
            log.info("No results to check. You have to specify at least one expected build result in the build-result trigger configuration.");
            return false;
        }
        if (buildId == null) {
            // no complete build was found so can't trigger here.
            return false;
        }

        Job jobObj = Jenkins.getActiveInstance().getItem(jobName, this.job.getParent(), Job.class);
        Run jobObjLastBuild = jobObj.getBuildByNumber(buildId.intValue());
        Result jobObjectLastResult = jobObjLastBuild.getResult();

        for (CheckedResult checkedResult : expectedResults) {
            log.info(String.format("Checking %s", checkedResult.getResult().toString()));
            if (checkedResult.getResult().ordinal == jobObjectLastResult.ordinal) {
                log.info(String.format("Last build result for the job %s matches the expected result %s.", jobName, jobObjectLastResult));
                return true;
            }
        }

        return false;
    }

    public boolean onJobRenamed(String fullOldName, String fullNewName) {
        boolean result = true;
        for (BuildResultTriggerInfo b : jobsInfo) {
            result &= b.onJobRenamed(fullOldName, fullNewName);
        }
        renameJobInSavedContext(fullOldName, fullNewName);
        return result;
    }

    @Extension
    @SuppressWarnings("unused")
    public static class BuildResultTriggerDescriptor extends XTriggerDescriptor {

        private transient final SequentialExecutionQueue queue = new SequentialExecutionQueue(Executors.newSingleThreadExecutor());

        @Override
        public ExecutorService getExecutor() {
            return queue.getExecutors();
        }

        @Override
        public boolean isApplicable(Item item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "[BuildResultTrigger] - Monitor build results of other jobs";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/buildresult-trigger/help.html";
        }
    }

    @Extension
    public static class ItemListenerImpl extends ItemListener {
        @Override
        public void onRenamed(Item item, String oldName, String newName) {
            String fullNewName = item.getFullName();
            String fullOldName = StringUtils.removeEnd(fullNewName, newName) + oldName;
            for (Project<?, ?> p : Jenkins.getActiveInstance().getAllItems(Project.class)) {
                BuildResultTrigger t = p.getTrigger(BuildResultTrigger.class);
                if (t != null) {
                    if (t.onJobRenamed(fullOldName, fullNewName)) {
                        try {
                            p.save();
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to persist project setting during rename from " + oldName + " to " + newName, e);
                        }
                    }
                }
            }
        }
    }
}
