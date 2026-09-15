// An activity must be closed by its schedule-to-close deadline. When its next retry would start
// after the deadline, the server fails it at once with RetryState.TIMEOUT. The test server skips
// that check whenever the deadline falls on a whole second, and then never enforces the deadline
// at all: the activity stays open past it.
//
// The deadline is the schedule time plus five minutes, so roughly one run in a thousand hits a
// whole second. The test keeps starting workflows until one does, or gives up after MAX_RUNS.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { Client, type WorkflowHandle } from '@temporalio/client';
import { ActivityFailure, RetryState } from '@temporalio/common';
import { TestWorkflowEnvironment } from '@temporalio/testing';
import { DefaultLogger, Runtime, Worker } from '@temporalio/worker';

import * as activities from './activities.ts';

const TASK_QUEUE = 'repro';
const MAX_RUNS = 20_000;
const STILL_RUNNING = Symbol('still running');

// The worker logs every failed activity attempt; the repro produces thousands.
Runtime.install({ logger: new DefaultLogger('ERROR') });

test('activity fails at schedule-to-close even when the deadline falls on a whole second', async () => {
  const env = await TestWorkflowEnvironment.createTimeSkipping();
  try {
    // A plain client: the environment's own client unlocks time skipping while awaiting a result.
    const client = new Client({ connection: env.connection });
    const worker = await Worker.create({
      connection: env.nativeConnection,
      taskQueue: TASK_QUEUE,
      workflowsPath: fileURLToPath(new URL('./workflows.ts', import.meta.url)),
      activities,
    });

    await worker.runUntil(async () => {
      for (let run = 1; run <= MAX_RUNS; run++) {
        const handle = await client.workflow.start('gateWorkflow', {
          taskQueue: TASK_QUEUE,
          workflowId: `gate-${run}`,
        });
        let outcome = await settleWithin(client, handle, 2_000);

        if (outcome === STILL_RUNNING) {
          const scheduled = (await handle.describe()).raw.pendingActivities?.[0];
          const deadlineMs = millis(scheduled?.expirationTime);
          await env.sleep(deadlineMs + 60_000 - (await env.currentTimeMs()));
          outcome = await settleWithin(client, handle, 2_000);

          if (outcome === STILL_RUNNING) {
            const { status, raw } = await handle.describe();
            const pending = raw.pendingActivities?.[0];
            assert.fail(
              [
                `Run ${run}: the activity is still open after its schedule-to-close deadline;` +
                  ` the previous ${run - 1} runs failed with RetryState.TIMEOUT as expected.`,
                `  first attempt scheduled at: ${iso(millis(scheduled?.scheduledTime))}`,
                `  last failure:               ${pending?.lastFailure?.message}`,
                `  schedule-to-close deadline: ${iso(deadlineMs)} (nanos=${scheduled?.expirationTime?.nanos ?? 0})`,
                `  test server time now:       ${iso(await env.currentTimeMs())}`,
                `  workflow status:            ${status.name}, activity attempt ${pending?.attempt}`,
              ].join('\n'),
            );
          }
        }

        assert.ok(outcome instanceof Error && outcome.cause instanceof ActivityFailure, `run ${run}: ${String(outcome)}`);
        assert.equal(outcome.cause.retryState, RetryState.TIMEOUT);
      }
    });
  } finally {
    await env.teardown();
  }
});

// Cancels the pending result() RPC after `ms` so a stuck run does not outlive the test.
async function settleWithin(client: Client, handle: WorkflowHandle, ms: number): Promise<unknown> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  try {
    return await client.connection.withAbortSignal(controller.signal, () => handle.result());
  } catch (error) {
    return controller.signal.aborted ? STILL_RUNNING : error;
  } finally {
    clearTimeout(timer);
  }
}

function millis(ts: { seconds?: unknown; nanos?: number | null } | null | undefined): number {
  return ts ? Number(ts.seconds ?? 0) * 1000 + Math.floor((ts.nanos ?? 0) / 1e6) : 0;
}

function iso(ms: number): string {
  return new Date(ms).toISOString();
}
