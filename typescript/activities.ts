import { ApplicationFailure } from '@temporalio/common';

export async function gate(): Promise<void> {
  throw ApplicationFailure.create({
    message: 'retryable failure; the next attempt would start after schedule-to-close',
    type: 'Outage',
    nextRetryDelay: '90 days',
  });
}
