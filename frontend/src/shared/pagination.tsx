import { useEffect } from 'react';
import type { Page } from '../api/contracts';

export async function readAllPages<T>(load: (page: number) => Promise<Page<T>>): Promise<T[]> {
  const items: T[] = [];
  let requestedPage = 0;
  for (;;) {
    const result = await load(requestedPage);
    const coherent = result.page === requestedPage
      && result.size > 0
      && result.totalElements >= 0
      && result.totalPages >= 0
      && result.hasNext === (result.page + 1 < result.totalPages)
      && result.items.length <= result.size;
    if (!coherent) throw new Error('分页元数据不一致，已停止继续读取');
    items.push(...result.items);
    if (!result.hasNext) {
      if (items.length !== result.totalElements) throw new Error('分页元数据与已读取数量不一致');
      return items;
    }
    requestedPage += 1;
  }
}

export function PaginationControls({ page, totalPages, hasNext, onPageChange, label }: {
  page: number;
  totalPages: number;
  hasNext: boolean;
  onPageChange: (page: number) => void;
  label: string;
}) {
  if (page === 0 && !hasNext) return null;
  return <nav className="pagination-controls" aria-label={`${label}分页`}>
    <button type="button" disabled={page === 0} onClick={() => onPageChange(Math.max(0, page - 1))}>上一页</button>
    <span>第 {page + 1} / {Math.max(1, totalPages)} 页</span>
    <button type="button" disabled={!hasNext} onClick={() => onPageChange(page + 1)}>下一页</button>
  </nav>;
}

export function usePageRecovery<T>(page: number, result: Page<T> | undefined, onPageChange: (page: number) => void) {
  useEffect(() => {
    if (!result || page === 0 || page < result.totalPages) return;
    onPageChange(Math.max(0, result.totalPages - 1));
  }, [page, result, onPageChange]);
}
