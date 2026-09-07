import { createContext, useCallback, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useBlocker } from 'react-router-dom';
import { ConfirmDialog } from '../features/common';

type Entry = { dirty: boolean; busy: boolean; discard: () => void };
type DraftRegistry = {
  entries: Map<string, Entry>;
  clear: () => void;
  changed: () => void;
};
const DraftContext = createContext<DraftRegistry | null>(null);

/** Optional so isolated forms also work without a data router. Drafts never leave memory. */
export function useDraftRegistry() { return useContext(DraftContext); }

export function DraftGuardProvider({ children }: { children: ReactNode }) {
  const entries = useRef(new Map<string, Entry>()).current;
  const [generation, setGeneration] = useState(0);
  const blocker = useBlocker(() => [...entries.values()].some(entry => entry.dirty || entry.busy));
  const proceeded = useRef<typeof blocker | null>(null);
  const proceed = useCallback(() => {
    if (blocker.state !== 'blocked' || proceeded.current === blocker) return;
    proceeded.current = blocker;
    blocker.proceed();
  }, [blocker]);
  const clear = useCallback(() => { for (const entry of entries.values()) entry.discard(); entries.clear(); setGeneration(value => value + 1); }, [entries]);
  const changed = useCallback(() => setGeneration(value => value + 1), []);
  const value = useMemo(() => ({ entries, clear, changed }), [entries, clear, changed]);
  const busy = [...entries.values()].some(entry => entry.busy);
  useEffect(() => {
    if (![...entries.values()].some(entry => entry.dirty || entry.busy)) proceed();
  }, [proceed, entries, generation]);
  return <DraftContext.Provider value={value}>{children}<ConfirmDialog
    open={blocker.state === 'blocked'} title={busy ? '正在保存，请稍候' : '放弃未保存的修改？'}
    detail={busy ? '保存完成后可以离开此页面。' : '离开后，本次尚未保存的输入将被清除。'}
    cancelLabel="继续编辑" confirmLabel="放弃修改" confirmDisabled={busy}
    onClose={() => blocker.state === 'blocked' && blocker.reset()}
    onConfirm={() => { if (!busy && blocker.state === 'blocked') { clear(); proceed(); } }}
  /></DraftContext.Provider>;
}

export function useDraftProtection({ active = true, draft, busy = false, sessionKey, savedKey, onDiscard }: {
  active?: boolean; draft?: unknown; busy?: boolean; sessionKey?: unknown; savedKey?: unknown; onDiscard?: () => void;
}) {
  const id = useId();
  const registry = useDraftRegistry();
  const serialized = JSON.stringify(draft);
  const baseline = useRef({ active: false, sessionKey, savedKey, value: serialized });
  if (!active || !baseline.current.active || baseline.current.sessionKey !== sessionKey || (savedKey !== undefined && baseline.current.savedKey !== savedKey)) {
    baseline.current = { active, sessionKey, savedKey, value: serialized };
  }
  const dirty = active && draft !== undefined && draft !== null && baseline.current.value !== serialized;
  const entry = useRef({ dirty, busy: active && busy, discard: onDiscard });
  entry.current = { dirty, busy: active && busy, discard: onDiscard };
  useLayoutEffect(() => {
    if (!active || !registry) return;
    // A getter keeps navigation predicates current even before passive effects run.
    registry.entries.set(id, { get dirty() { return entry.current.dirty; }, get busy() { return entry.current.busy; }, discard() { entry.current.dirty = false; entry.current.busy = false; entry.current.discard?.(); } });
    registry.changed();
    return () => { registry.entries.delete(id); registry.changed(); };
  }, [active, id, registry, dirty, busy]);
  useLayoutEffect(() => { registry?.changed(); }, [dirty, busy, registry]);
  useEffect(() => {
    if (!dirty && !busy) return;
    const beforeUnload = (event: BeforeUnloadEvent) => { if (entry.current.dirty || entry.current.busy) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty, busy]);
  return { dirty, busy: active && busy, release: () => { entry.current.dirty = false; entry.current.busy = false; registry?.entries.delete(id); } };
}
