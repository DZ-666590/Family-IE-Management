import { useLayoutEffect, useRef, useState } from 'react';
import type Select from '@douyinfe/semi-ui/lib/es/select';

/** Keep both market selectors anchored; Escape never leaks into an editor. */
export function useStockDropdown(pickerId: string) {
  const selectRef = useRef<Select>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [controlWidth, setControlWidth] = useState(0);
  useLayoutEffect(() => {
    const picker = document.getElementById(pickerId);
    if (!picker) return;
    const measure = () => setControlWidth(picker.getBoundingClientRect().width);
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(picker);
    return () => observer?.disconnect();
  }, [pickerId]);
  useLayoutEffect(() => {
    if (!menuOpen) return;
    const stopEditorEscape = (event: KeyboardEvent) => {
      const picker = document.getElementById(pickerId);
      if (event.key !== 'Escape' || !picker?.contains(event.target as Node)) return;
      event.preventDefault(); event.stopPropagation(); event.stopImmediatePropagation();
      selectRef.current?.close();
    };
    window.addEventListener('keydown', stopEditorEscape, true);
    return () => window.removeEventListener('keydown', stopEditorEscape, true);
  }, [menuOpen, pickerId]);
  return { selectRef, controlWidth, setMenuOpen };
}
