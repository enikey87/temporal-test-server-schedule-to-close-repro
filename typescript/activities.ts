import { ApplicationFailure } from '@temporalio/common';

export async function gate(): Promise<void> {
  throw ApplicationFailure.create({ message: 'retryable failure', nextRetryDelay: '90 days' });
}
