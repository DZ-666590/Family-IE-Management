/** Presentation-only decimal rounding. Never round through binary floating point. */
export function formatMoney(value:string|number|null|undefined,currency='CNY'):string {
  if(value==null||value==='')return '—';
  let raw=String(value).trim();
  if(typeof value==='number'&&Number.isFinite(value)&&/[eE]/.test(raw))raw=value.toFixed(2);
  const match=/^(-?)(\d+)(?:\.(\d+))?$/.exec(raw);
  if(!match)return String(value);
  const fraction=match[3]??'';
  const cents=BigInt(match[2])*100n+BigInt(fraction.padEnd(2,'0').slice(0,2))+(Number(fraction[2]??0)>=5?1n:0n);
  const whole=String(cents/100n).replace(/\B(?=(\d{3})+(?!\d))/g,',');
  return `${match[1]&&cents>0n?'-':''}${currency==='CNY'?'¥':currency+' '}${whole}.${String(cents%100n).padStart(2,'0')}`;
}
