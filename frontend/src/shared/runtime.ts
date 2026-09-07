const businessDateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
});

export function businessDate(date: Date = new Date()): string {
  const parts = new Map(businessDateFormatter.formatToParts(date).map(part => [part.type, part.value]));
  return `${parts.get('year')}-${parts.get('month')}-${parts.get('day')}`;
}

export function localYearMonth(date: Date = new Date()): string {
  return businessDate(date).slice(0, 7);
}

// Opaque request key, not a credential. getRandomValues also works on HTTP,
// unlike randomUUID, which browsers restrict to secure contexts.
export function newIdempotencyKey(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
}

export class RefreshGate {
  private generation = 0;

  invalidate(): void {
    this.generation += 1;
  }

  async run<T>(work: () => Promise<T>, commit: (value: T) => void): Promise<{ current: boolean }> {
    const generation = ++this.generation;
    try {
      const value = await work();
      if (generation !== this.generation) return { current: false };
      commit(value);
      return { current: true };
    } catch (error) {
      if (generation !== this.generation) return { current: false };
      throw error;
    }
  }
}

export interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T | PromiseLike<T>) => void;
  reject: (reason?: unknown) => void;
}

export function deferred<T>(): Deferred<T> {
  let resolve!: Deferred<T>['resolve'];
  let reject!: Deferred<T>['reject'];
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
