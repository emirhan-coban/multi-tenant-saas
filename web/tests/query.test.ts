import { expect, test } from 'vitest';
import { createQueryClient, clearSessionQueries } from '../src/query/client';
import { queryKeys } from '../src/query/keys';

test('tenant and staff caches are isolated even when resource IDs match', async () => {
  const client = createQueryClient();
  await client.fetchQuery({ queryKey: queryKeys.services('tenant-a'), queryFn: async () => ['A'] });
  await client.fetchQuery({ queryKey: queryKeys.services('tenant-b'), queryFn: async () => ['B'] });
  client.setQueryData(queryKeys.workingHours('tenant-a', 'staff-1'), ['09:00']);
  client.setQueryData(queryKeys.workingHours('tenant-a', 'staff-2'), ['10:00']);
  await client.invalidateQueries({ queryKey: queryKeys.tenant('tenant-a') });
  expect(client.getQueryState(queryKeys.services('tenant-a'))?.isInvalidated).toBe(true);
  expect(client.getQueryState(queryKeys.services('tenant-b'))?.isInvalidated).toBe(false);
  expect(client.getQueryData(queryKeys.services('tenant-b'))).toEqual(['B']);
  expect(client.getQueryData(queryKeys.workingHours('tenant-a', 'staff-1'))).toEqual(['09:00']);
  expect(client.getQueryData(queryKeys.workingHours('tenant-a', 'staff-2'))).toEqual(['10:00']);
  client.clear();
});

test('session reset aborts queries and late results do not repopulate the cache', async () => {
  const client = createQueryClient();
  let signal!: AbortSignal;
  let release!: (data: string[]) => void;
  const pending = client.fetchQuery({
    queryKey: queryKeys.services('old-tenant'),
    queryFn: context => {
      signal = context.signal;
      return new Promise<string[]>(resolve => { release = resolve; });
    },
  }).catch(() => undefined);
  client.setQueryData(queryKeys.me, { tenantId: 'old-tenant' });
  clearSessionQueries(client);
  expect(signal.aborted).toBe(true);
  release(['Old private data']);
  await pending;
  expect(client.getQueryCache().getAll()).toHaveLength(0);
  expect(client.getMutationCache().getAll()).toHaveLength(0);
});
