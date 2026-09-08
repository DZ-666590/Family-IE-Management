import { formatMoney } from './currency';
it('formats decimal strings without losing cents to floating point',()=>{
  expect(formatMoney('92233720368547758.07')).toBe('¥92,233,720,368,547,758.07');
  expect(formatMoney('1.005','USD')).toBe('USD 1.01');
  expect(formatMoney('-0.004','HKD')).toBe('HKD 0.00');
  expect(formatMoney('1000','USD')).toBe('USD 1,000.00');
  expect(formatMoney(null,'USD')).toBe('—');
});
