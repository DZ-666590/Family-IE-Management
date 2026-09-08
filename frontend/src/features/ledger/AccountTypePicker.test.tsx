import { render,screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { AccountTypePicker,type AccountClassification } from './AccountTypePicker';

function Form(){const [value,setValue]=useState<AccountClassification>({type:'BANK',walletProvider:''});return <AccountTypePicker value={value} onChange={setValue}/>;}
it('lets people select Alipay and WeChat directly by platform',async()=>{
  render(<Form/>);
  await userEvent.click(screen.getByRole('radio',{name:'支付宝'}));
  expect(screen.getByRole('radio',{name:'支付宝'})).toBeChecked();
  await userEvent.click(screen.getByRole('radio',{name:'微信'}));
  expect(screen.getByRole('radio',{name:'微信'})).toBeChecked();
  expect(screen.getByRole('radio',{name:'支付宝'})).not.toBeChecked();
});
it('does not silently classify historical wallets',()=>{
  render(<AccountTypePicker value={{type:'WALLET',walletProvider:''}} onChange={()=>{}}/>);
  expect(screen.getByRole('radio',{name:'未细分电子钱包'})).toBeChecked();
  expect(screen.getByRole('radio',{name:'支付宝'})).not.toBeChecked();
});
