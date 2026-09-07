import { useState } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Drawer, FormError } from './common';
import { FlowChart, HistoryChart } from './visuals';
import { ApiError } from '../api/client';

it('traps focus in the top drawer and closes nested drawers one at a time', async () => {
  function Demo() {
    const [outer,setOuter] = useState(false), [inner,setInner] = useState(false);
    return <><button onClick={()=>setOuter(true)}>打开</button><Drawer title="父层" open={outer} onClose={()=>setOuter(false)}><button onClick={()=>setInner(true)}>下一层</button></Drawer><Drawer title="子层" open={inner} onClose={()=>setInner(false)}><input aria-label="内容"/></Drawer></>;
  }
  const user=userEvent.setup(); render(<Demo/>);
  await user.click(screen.getByRole('button',{name:'打开'}));
  await user.click(screen.getByRole('button',{name:'下一层'}));
  const dialog=screen.getByRole('dialog',{name:'子层'});
  await user.tab({shift:true}); expect(within(dialog).getByLabelText('内容')).toHaveFocus();
  await user.keyboard('{Escape}'); expect(screen.queryByRole('dialog',{name:'子层'})).not.toBeInTheDocument();
  expect(screen.getByRole('button',{name:'下一层'})).toHaveFocus();
  expect(document.body.style.overflow).toBe('hidden');
  await user.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('button',{name:'打开'})).toHaveFocus();
  expect(document.body.style.overflow).not.toBe('hidden');
});

it('shows field guidance and never paints a nonzero bar for zero money', () => {
  const {container}=render(<><FormError error={new ApiError('请检查输入内容',{status:400,fields:{amount:'金额必须大于零'}})}/><FlowChart points={[{label:'1月',income:'0.00',expense:'0.00'}]}/><HistoryChart data={[{snapshotOn:'2026-09-02',asset:'0.00',liability:'20.00',netWorth:'-20.00',accountingBasis:'LEDGER_AS_OF',valuationEstimated:false,unpricedPositions:0},{snapshotOn:'2026-09-01',asset:'10.00',liability:'0.00',netWorth:'10.00',accountingBasis:'LEDGER_AS_OF',valuationEstimated:false,unpricedPositions:0}]}/></>);
  expect(screen.getByText('金额必须大于零')).toBeInTheDocument();
  const bars=container.querySelectorAll('.flow-svg g[role="button"] rect:not(:first-child)');
  expect([...bars].every(bar=>bar.getAttribute('height')==='0')).toBe(true);
  expect([...container.querySelectorAll('.history-figure circle title')].map(item=>item.textContent)).toEqual(['2026-09-01：¥10.00 · 按当日有效估值 · 按生效日期重算','2026-09-02：-¥20.00 · 按当日有效估值 · 按生效日期重算']);
});

it('links grouped validation errors to the relevant editable fieldset', () => {
  render(<form><FormError error={new ApiError('请检查期次', { status: 400, fields: { customSchedule: '期次数量不匹配' } })} /><fieldset tabIndex={-1} data-field="customSchedule"><legend>自定义期次</legend><input aria-label="期次金额" /></fieldset></form>);
  expect(screen.getByRole('group', { name: '自定义期次' })).toHaveFocus();
  expect(screen.getByRole('group', { name: '自定义期次' })).toHaveAccessibleDescription('期次数量不匹配');
});
