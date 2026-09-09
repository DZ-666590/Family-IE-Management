import { useEffect, useState, type CompositionEvent, type KeyboardEvent } from 'react';

/** Debounce committed text, never an in-progress Chinese IME composition. */
export function useStockSearch() {
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  const [composing, setComposing] = useState(false);
  useEffect(() => {
    if (composing) return;
    const timer = setTimeout(() => setDebounced(query.trim()), 250);
    return () => clearTimeout(timer);
  }, [query, composing]);
  return { query, setQuery, debounced, composing, compositionProps: {
    onCompositionStartCapture: () => setComposing(true),
    onCompositionEndCapture: (event: CompositionEvent) => {
      if (event.target instanceof HTMLInputElement) setQuery(event.target.value);
      setComposing(false);
    },
    onKeyDownCapture: (event: KeyboardEvent) => {
      if (composing || event.nativeEvent.isComposing || event.keyCode === 229) event.stopPropagation();
    }
  }};
}
