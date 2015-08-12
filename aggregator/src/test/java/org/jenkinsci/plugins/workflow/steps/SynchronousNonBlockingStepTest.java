package org.jenkinsci.plugins.workflow.steps;

import static org.junit.Assert.assertTrue;
import hudson.model.TaskListener;
import hudson.model.Run;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.inject.Inject;

public class SynchronousNonBlockingStepTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void basicNonBlockingStep() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {\n" +
            "echo 'First message'\n" +
            "syncnonblocking 'wait'\n" +
            "echo 'Second message'\n" +
        "}"));
        WorkflowRun b = p.scheduleBuild2(0).getStartCondition().get();

        // Wait for syncnonblocking to be started
        System.out.println("Waiting to syncnonblocking to start...");
        SynchronousNonBlockingStep.waitForStart("wait", b);

        // At this point the execution is paused inside the synchronous non-blocking step
        // Check for FlowNode created
        FlowGraphWalker walker = new FlowGraphWalker(b.getExecution());
        boolean found = false;
        for (FlowNode n = walker.next(); n != null; n = walker.next()) {
            if (n instanceof StepNode) {
                found = true;
                break;
            }
        }

        System.out.println("Checking flow node added...");
        assertTrue("FlowNode has to be added just when the step starts running", found);

        // Check for message the test message sent to context listener
        System.out.println("Checking build log message present...");
        j.waitForMessage("Test Sync Message", b);
        // The last step did not run yet
        j.assertLogNotContains("Second message", b);

        // Let syncnonblocking to continue
        SynchronousNonBlockingStep.notify("wait");

        System.out.println("Waiting until syncnonblocking (and the full flow) finishes");
        j.waitForCompletion(b);
        System.out.println("Build finished. Continue.");
        // Check for the last message
        j.assertLogContains("Second message", b);
        j.assertBuildStatusSuccess(b);
    }

    public static final class SynchronousNonBlockingStep extends AbstractStepImpl implements Serializable {

        public static final class State {
            private static State state;
            static synchronized State get() {
                if (state == null) {
                    state = new State();
                    return state;
                }
                return state;
            }
            private State() {}
            final Set<String> started = new HashSet<String>();
        }

        private String id;

        @DataBoundConstructor
        public SynchronousNonBlockingStep(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static void waitForStart(String id, Run<?,?> b) throws IOException, InterruptedException {
            State s = State.get();
            synchronized (s) {
                while (!s.started.contains(id)) {
                    if (b != null && !b.isBuilding()) {
                        throw new AssertionError(JenkinsRule.getLog(b));
                    }
                    s.wait(1000);
                }
            }
        }

        public static final void notify(String id) {
            State s = State.get();
            synchronized (s) {
                if (s.started.contains(id)) {
                    s.started.remove(id);
                    s.notifyAll();
                }
            }
        }

        public static class StepExecutionImpl extends AbstractSynchronousNonBlockingStepExecution<Void> {

            @Inject(optional=true) 
            private SynchronousNonBlockingStep step;

            @Override
            protected Void run() throws Exception {
                System.out.println("Starting syncnonblocking " + step.getId());
                // Send a test message to the listener
                getContext().get(TaskListener.class).getLogger().println("Test Sync Message");

                State s = State.get();
                synchronized (s) {
                    s.started.add(step.getId());
                    s.notifyAll();
                }

                // Let's wait 2 additional seconds after unblocking the test thread
                // During this 2 seconds it will check for the messages in the build log
                System.out.println("Sleeping inside the syncnonblocking thread");
                synchronized (s) {
                    s.wait();
                }
                System.out.println("Continue syncnonblocking");

                return null;
            }

            private static final long serialVersionUID = 1L;
        }

        @TestExtension
        public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

            public DescriptorImpl() {
                super(StepExecutionImpl.class);
            }

            @Override
            public String getFunctionName() {
                return "syncnonblocking";
            }

            @Override
            public String getDisplayName() {
                return "Sync non-blocking Test step";
            }

        }

        private static final long serialVersionUID = 1L;

    }


}