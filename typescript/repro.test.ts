// Expected: an activity is closed by its schedule-to-close deadline. Its next retry is 90 days
//           away, so it fails right after the first attempt with RetryState.TIMEOUT.
// Actual:   when the deadline falls on a whole second (about 1 run in 1000), the test server
//           schedules the retry anyway, and the activity is still open after the deadline.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { Client, type WorkflowHandle, WorkflowFailedError } from '@temporalio/client';
import { ActivityFailure, RetryState } from '@temporalio/common';
import { TestWorkflowEnvironment } from '@temporalio/testing';
import { DefaultLogger, Runtime, Worker } from '@temporalio/worker';

import * as activities from './activities.ts';

Runtime.install({ logger: new DefaultLogger('ERROR') }); // silences a warning per failed attempt

test('activity fails at schedule-to-close even when the deadline falls on a whole second', async () => {
  const env = await TestWorkflowEnvironment.createTimeSkipping();
  const client = new Client({ connection: env.connection }); // unlike env.client, never skips time
  const worker = await Worker.create({
    connection: env.nativeConnection,
    taskQueue: 'repro',
    workflowsPath: fileURLToPath(new URL('./workflows.ts', import.meta.url)),
    activities,
  });

  try {
    await worker.runUntil(async () => {
      for (let run = 1; run <= 20_000; run++) {
        const workflow = await client.workflow.start('gateWorkflow', { taskQueue: 'repro', workflowId: `run-${run}` });
        if (await failsWithin2s(client, workflow)) continue;

        // Stuck: move the test server's clock a minute past the deadline and look again.
        const deadline = toMillis((await pendingActivity(workflow)).expirationTime);
        await env.sleep(deadline + 60_000 - (await env.currentTimeMs()));
        if (await failsWithin2s(client, workflow)) continue;

        const pending = await pendingActivity(workflow);
        assert.fail(
          [
            `Run ${run}: the activity is still open after its schedule-to-close deadline (the previous ${run - 1} runs failed as expected).`,
            `  deadline:    ${new Date(deadline).toISOString()} (nanos=${pending.expirationTime?.nanos ?? 0})`,
            `  server time: ${new Date(await env.currentTimeMs()).toISOString()}`,
            `  attempt:     ${pending.attempt}`,
          ].join('\n'),
        );
      }
    });
  } finally {
    await env.teardown();
  }
});

/** True if the workflow failed with RetryState.TIMEOUT within 2s, false if it is still running. */
async function failsWithin2s(client: Client, workflow: WorkflowHandle): Promise<boolean> {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), 2_000);
  let error: unknown;
  try {
    await client.connection.withAbortSignal(timeout.signal, () => workflow.result());
  } catch (e) {
    error = e;
  } finally {
    clearTimeout(timer);
  }
  if (timeout.signal.aborted) return false;

  assert.ok(error instanceof WorkflowFailedError && error.cause instanceof ActivityFailure, 'expected the workflow to fail');
  assert.equal(error.cause.retryState, RetryState.TIMEOUT);
  return true;
}

async function pendingActivity(workflow: WorkflowHandle) {
  const { raw } = await workflow.describe();
  return raw.pendingActivities![0];
}

function toMillis(timestamp: { seconds?: unknown; nanos?: number | null } | null | undefined): number {
  return Number(timestamp?.seconds) * 1000 + Math.floor((timestamp?.nanos ?? 0) / 1e6);
}
