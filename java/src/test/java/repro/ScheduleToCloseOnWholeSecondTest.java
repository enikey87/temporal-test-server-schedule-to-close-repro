package repro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.Timestamp;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.workflow.v1.PendingActivityInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Expected: an activity is closed by its schedule-to-close deadline. Its next retry is 90 days
 * away, so it fails right after the first attempt with RETRY_STATE_TIMEOUT.
 *
 * <p>Actual: when the deadline falls on a whole second (about 1 run in 1000), the test server
 * schedules the retry anyway, and the activity is still open after the deadline.
 */
class ScheduleToCloseOnWholeSecondTest {

  @ActivityInterface
  public interface Gate {
    void check();
  }

  public static class FailingGate implements Gate {
    @Override
    public void check() {
      throw ApplicationFailure.newBuilder()
          .setMessage("retryable failure")
          .setType("RetryableFailure") // required: without a type the SDK cannot report the failure
          .setNextRetryDelay(Duration.ofDays(90))
          .build();
    }
  }

  @WorkflowInterface
  public interface GateWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class GateWorkflowImpl implements GateWorkflow {
    private final Gate gate =
        Workflow.newActivityStub(
            Gate.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(5)).build());

    @Override
    public void run() {
      gate.check();
    }
  }

  @Test
  void failsAtScheduleToCloseEvenWhenDeadlineFallsOnWholeSecond() {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker("repro");
    worker.registerWorkflowImplementationTypes(GateWorkflowImpl.class);
    worker.registerActivitiesImplementations(new FailingGate());
    env.start();
    // Unlike env.getWorkflowClient(), this client never skips time while waiting for a result.
    WorkflowClient client =
        WorkflowClient.newInstance(
            env.getWorkflowServiceStubs(),
            WorkflowClientOptions.newBuilder().setNamespace(env.getNamespace()).build());

    try {
      for (int run = 1; run <= 20_000; run++) {
        String workflowId = "run-" + run;
        WorkflowStub workflow =
            client.newUntypedWorkflowStub(
                "GateWorkflow",
                WorkflowOptions.newBuilder().setTaskQueue("repro").setWorkflowId(workflowId).build());
        workflow.start();
        if (failsWithin2s(workflow)) continue;

        // Stuck: move the test server's clock a minute past the deadline and look again.
        Timestamp deadline = pendingActivity(env, workflowId).getExpirationTime();
        env.sleep(Duration.ofMillis(toMillis(deadline) + 60_000 - env.currentTimeMillis()));
        if (failsWithin2s(workflow)) continue;

        fail(
            String.format(
                "Run %d: the activity is still open after its schedule-to-close deadline"
                    + " (the previous %d runs failed as expected).%n"
                    + "  deadline:    %s (nanos=%d)%n"
                    + "  server time: %s%n"
                    + "  attempt:     %d",
                run,
                run - 1,
                Instant.ofEpochSecond(deadline.getSeconds(), deadline.getNanos()),
                deadline.getNanos(),
                Instant.ofEpochMilli(env.currentTimeMillis()),
                pendingActivity(env, workflowId).getAttempt()));
      }
    } finally {
      env.close();
    }
  }

  /** True if the workflow failed with RETRY_STATE_TIMEOUT within 2s, false if it is still running. */
  private static boolean failsWithin2s(WorkflowStub workflow) {
    try {
      workflow.getResult(2, TimeUnit.SECONDS, Void.class);
    } catch (TimeoutException e) {
      return false;
    } catch (WorkflowFailedException e) {
      assertEquals(
          RetryState.RETRY_STATE_TIMEOUT, ((ActivityFailure) e.getCause()).getRetryState());
      return true;
    }
    throw new AssertionError("expected the workflow to fail");
  }

  private static PendingActivityInfo pendingActivity(TestWorkflowEnvironment env, String workflowId) {
    return env.getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(env.getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                .build())
        .getPendingActivities(0);
  }

  private static long toMillis(Timestamp timestamp) {
    return timestamp.getSeconds() * 1000 + timestamp.getNanos() / 1_000_000;
  }
}
