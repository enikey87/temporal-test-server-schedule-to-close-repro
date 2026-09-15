package repro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.Timestamp;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.workflow.v1.PendingActivityInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * An activity whose next retry would start after its schedule-to-close deadline must fail with
 * RETRY_STATE_TIMEOUT. The test server skips that check whenever the deadline falls on a whole
 * second, so the activity is rescheduled and the workflow stays running.
 *
 * <p>The deadline is the schedule time plus five minutes, so roughly one run in a thousand hits a
 * whole second. The test keeps starting workflows until one does, or gives up after
 * MAX_ATTEMPTS.
 */
class ScheduleToCloseOnWholeSecondTest {

  private static final String TASK_QUEUE = "repro";
  private static final int MAX_ATTEMPTS = 20_000;
  private static final DateTimeFormatter MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  @ActivityInterface
  public interface Gate {
    @ActivityMethod
    void check();
  }

  public static class FailingGate implements Gate {
    @Override
    public void check() {
      throw ApplicationFailure.newBuilder()
          .setMessage("retryable failure; the next attempt would start after schedule-to-close")
          .setType("Outage")
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
  void activityFailsWhenItsNextRetryWouldStartAfterScheduleToClose() throws Exception {
    // Time skipping is not needed: the activity should fail as soon as its first attempt does.
    TestWorkflowEnvironment env =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
    try {
      Worker worker = env.newWorker(TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(GateWorkflowImpl.class);
      worker.registerActivitiesImplementations(new FailingGate());
      env.start();
      WorkflowClient client = env.getWorkflowClient();

      for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        String workflowId = "gate-" + attempt;
        GateWorkflow workflow =
            client.newWorkflowStub(
                GateWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE)
                    .setWorkflowId(workflowId)
                    .build());
        WorkflowClient.start(workflow::run);
        try {
          WorkflowStub.fromTyped(workflow).getResult(2, TimeUnit.SECONDS, Void.class);
          fail("workflow " + workflowId + " unexpectedly completed");
        } catch (WorkflowFailedException e) {
          ActivityFailure activityFailure = (ActivityFailure) e.getCause();
          assertEquals(RetryState.RETRY_STATE_TIMEOUT, activityFailure.getRetryState());
        } catch (TimeoutException e) {
          PendingActivityInfo pending = pendingActivity(env, client, workflowId);
          fail(
              String.format(
                  "Run %d: workflow is still running 2s after its activity failed;"
                      + " the previous %d runs failed with RETRY_STATE_TIMEOUT as expected.%n"
                      + "  activity state:             %s, attempt %d%n"
                      + "  first attempt scheduled at: %s%n"
                      + "  last attempt completed at:  %s%n"
                      + "  last failure:               %s%n"
                      + "  schedule-to-close deadline: %s (nanos=%d)%n"
                      + "The next retry is 90 days away, past the deadline, yet the activity was"
                      + " rescheduled instead of failing.",
                  attempt,
                  attempt - 1,
                  pending.getState(),
                  pending.getAttempt(),
                  format(pending.getScheduledTime()),
                  format(pending.getLastAttemptCompleteTime()),
                  pending.getLastFailure().getMessage(),
                  format(pending.getExpirationTime()),
                  pending.getExpirationTime().getNanos()));
        }
      }
    } finally {
      env.close();
    }
  }

  private static PendingActivityInfo pendingActivity(
      TestWorkflowEnvironment env, WorkflowClient client, String workflowId) {
    return env.getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                .build())
        .getPendingActivities(0);
  }

  private static String format(Timestamp timestamp) {
    return MILLIS.format(Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()));
  }
}
