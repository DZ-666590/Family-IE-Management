export function StockLabel({ name, code, exchange, accessibleLabel }: { name: string; code: string; exchange: string; accessibleLabel: string }) {
  return <span className="stock-picker-option-content"><span className="sr-only">{accessibleLabel}</span><strong className="stock-picker-option-name" aria-hidden="true">{name}</strong><span className="stock-picker-option-meta" aria-hidden="true"><span className="stock-picker-option-code">{code}</span><span className="stock-picker-option-exchange">{exchange}</span></span></span>;
}
