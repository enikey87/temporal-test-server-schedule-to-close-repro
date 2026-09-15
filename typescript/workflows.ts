import { proxyActivities } from '@temporalio/workflow';
import type * as activities from './activities.ts';

const { gate } = proxyActivities<typeof activities>({ scheduleToCloseTimeout: '5 minutes' });

export async function gateWorkflow(): Promise<void> {
  await gate();
}
