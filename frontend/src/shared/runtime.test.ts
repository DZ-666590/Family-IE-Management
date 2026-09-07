import { businessDate, deferred, localYearMonth, RefreshGate } from './runtime';

it('uses the Asia Shanghai business date', () => {
  expect(businessDate(new Date('2026-09-08T07:30:00+08:00'))).toBe('2026-09-08');
});

it('uses the same Asia Shanghai day for the selected month', () => {
  expect(localYearMonth(new Date('2027-01-01T00:01:00+08:00'))).toBe('2027-01');
});

it('does not let an obsolete refresh overwrite the newest result', async () => {
  const gate = new RefreshGate();
  const older = deferred<string>();
  const newer = deferred<string>();
  const committed: string[] = [];

  const olderRun = gate.run(() => older.promise, value => committed.push(value));
  const newerRun = gate.run(() => newer.promise, value => committed.push(value));
  newer.resolve('newest');
  await expect(newerRun).resolves.toEqual({ current: true });
  older.resolve('older');
  await expect(olderRun).resolves.toEqual({ current: false });
  expect(committed).toEqual(['newest']);
});

it('suppresses a late failure after invalidation', async () => {
  const gate = new RefreshGate();
  const pending = deferred<string>();
  const run = gate.run(() => pending.promise, () => undefined);
  gate.invalidate();
  pending.reject(new Error('obsolete request'));
  await expect(run).resolves.toEqual({ current: false });
});
