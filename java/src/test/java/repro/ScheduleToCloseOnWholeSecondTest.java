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
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
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
 * An activity must be closed by its schedule-to-close deadline. When its next retry would start
 * after the deadline, the server fails it at once with RETRY_STATE_TIMEOUT. The test server skips
 * that check whenever the deadline falls on a whole second, and then never enforces the deadline at
 * all: the activity stays open past it.
 *
 * <p>The deadline is the schedule time plus five minutes, so roughly one run in a thousand hits a
 * whole second. The test keeps starting workflows until one does, or gives up after MAX_RUNS.
 */
class ScheduleToCloseOnWholeSecondTest {

  private static final String TASK_QUEUE = "repro";
  private static final int MAX_RUNS = 20_000;

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
  void failsAtScheduleToCloseEvenWhenDeadlineFallsOnWholeSecond() throws Exception {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    try {
      Worker worker = env.newWorker(TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(GateWorkflowImpl.class);
      worker.registerActivitiesImplementations(new FailingGate());
      env.start();
      // A plain client: the environment's own client unlocks time skipping while awaiting a result.
      WorkflowClient client =
          WorkflowClient.newInstance(
              env.getWorkflowServiceStubs(),
              WorkflowClientOptions.newBuilder().setNamespace(env.getNamespace()).build());

      for (int run = 1; run <= MAX_RUNS; run++) {
        String workflowId = "gate-" + run;
        GateWorkflow workflow =
            client.newWorkflowStub(
                GateWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE)
                    .setWorkflowId(workflowId)
                    .build());
        WorkflowClient.start(workflow::run);
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        if (closedWithTimeout(stub)) {
          continue;
        }

        PendingActivityInfo scheduled = describe(env, workflowId).getPendingActivities(0);
        long deadlineMillis = millis(scheduled.getExpirationTime());
        env.sleep(Duration.ofMillis(deadlineMillis + 60_000 - env.currentTimeMillis()));
        if (closedWithTimeout(stub)) {
          continue;
        }

        DescribeWorkflowExecutionResponse after = describe(env, workflowId);
        PendingActivityInfo pending = after.getPendingActivities(0);
        fail(
            String.format(
                "Run %d: the activity is still open after its schedule-to-close deadline;"
                    + " the previous %d runs failed with RETRY_STATE_TIMEOUT as expected.%n"
                    + "  first attempt scheduled at: %s%n"
                    + "  last failure:               %s%n"
                    + "  schedule-to-close deadline: %s (nanos=%d)%n"
                    + "  test server time now:       %s%n"
                    + "  workflow status:            %s, activity attempt %d",
                run,
                run - 1,
                Instant.ofEpochMilli(millis(scheduled.getScheduledTime())),
                pending.getLastFailure().getMessage(),
                Instant.ofEpochMilli(deadlineMillis),
                scheduled.getExpirationTime().getNanos(),
                Instant.ofEpochMilli(env.currentTimeMillis()),
                after.getWorkflowExecutionInfo().getStatus(),
                pending.getAttempt()));
      }
    } finally {
      env.close();
    }
  }

  /** False when the workflow is still running after two seconds. */
  private static boolean closedWithTimeout(WorkflowStub stub) {
    try {
      stub.getResult(2, TimeUnit.SECONDS, Void.class);
      fail("workflow unexpectedly completed");
    } catch (WorkflowFailedException e) {
      assertEquals(RetryState.RETRY_STATE_TIMEOUT, ((ActivityFailure) e.getCause()).getRetryState());
      return true;
    } catch (TimeoutException e) {
      return false;
    }
    return true;
  }

  private static DescribeWorkflowExecutionResponse describe(
      TestWorkflowEnvironment env, String workflowId) {
    return env.getWorkflowServiceStubs()
        .blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(env.getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                .build());
  }

  private static long millis(Timestamp timestamp) {
    return timestamp.getSeconds() * 1000 + timestamp.getNanos() / 1_000_000;
  }
}
