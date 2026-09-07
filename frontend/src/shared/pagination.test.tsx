import { readAllPages } from './pagination';

it('reads every page including an exact full last page', async () => {
  const load = vi.fn(async (page: number) => ({
    items: page === 0 ? Array.from({ length: 50 }, (_, id) => id) : Array.from({ length: 50 }, (_, id) => id + 50),
    page,
    size: 50,
    totalElements: 100,
    totalPages: 2,
    hasNext: page === 0
  }));

  await expect(readAllPages(load)).resolves.toHaveLength(100);
  expect(load).toHaveBeenCalledTimes(2);
});

it('rejects non-advancing or inconsistent page metadata', async () => {
  const load = vi.fn(async () => ({ items: [1], page: 0, size: 1, totalElements: 2, totalPages: 2, hasNext: true }));

  await expect(readAllPages(load)).rejects.toThrow(/分页元数据/);
  expect(load).toHaveBeenCalledTimes(2);
});
