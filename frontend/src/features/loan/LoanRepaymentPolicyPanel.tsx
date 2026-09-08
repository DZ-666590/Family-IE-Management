import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { LoanRepaymentPolicy } from '../../api/contracts';
import { ApiError } from '../../api/client';
import { FormError, money, type RequestFn } from '../common';

type PolicyDraft = { minimumInstallmentAmount: string; sourceNote: string; revision: number };
export function LoanRepaymentPolicyPanel({ loanId, sessionKey, request, disabled, onDraftChange, onChanged, onBusyChange }: {
 loanId: number; sessionKey: string; request: RequestFn; disabled: boolean;
 onDraftChange: (draft: PolicyDraft | null) => void; onChanged: () => void; onBusyChange: (busy: boolean) => void;
}) {
 const [draft, setDraft] = useState<PolicyDraft | null>(null);
 const [reloaded, setReloaded] = useState(false);
 const [reloadError, setReloadError] = useState<unknown>(null);
 const policy = useQuery({ queryKey: ['loan-repayment-policy', loanId, sessionKey], queryFn: () => request<LoanRepaymentPolicy>(`/api/loans/${loanId}/repayment-policy`, { handleUnauthorized: false }), retry: false, refetchOnWindowFocus: false });
 const edit = (next: PolicyDraft | null) => { setDraft(next); onDraftChange(next); };
 const save = useMutation({ mutationFn: (value: PolicyDraft) => request<LoanRepaymentPolicy>(`/api/loans/${loanId}/repayment-policy`, { method: 'PATCH', handleUnauthorized: false, body: { minimumInstallmentAmount: value.minimumInstallmentAmount.trim() || null, sourceNote: value.sourceNote.trim() || null, revision: value.revision } }),
  onMutate: () => onBusyChange(true),
  onSuccess: async () => { await policy.refetch(); onChanged(); edit(null); setReloaded(false); },
  onSettled: () => onBusyChange(false)
 });
 const conflict = save.error instanceof ApiError && save.error.code === 'LOAN_POLICY_CHANGED';
 const reload = async () => {
  setReloadError(null); onBusyChange(true);
  try { const result = await policy.refetch({ throwOnError: true }); if (result.data && draft) { edit({ ...draft, revision: result.data.revision }); setReloaded(true); save.reset(); } }
  catch (error) { setReloadError(error); }
  finally { onBusyChange(false); }
 };
 return <section className="loan-repayment-policy" aria-label="合同还款规则"><details><summary>合同还款规则</summary>
  <FormError error={save.error ?? reloadError ?? policy.error} />
  {policy.data && <>
   <p className="source-note">{policy.data.minimumInstallmentAmount ? `已录入合同最低常规每期还款额 ${money(policy.data.minimumInstallmentAmount)}；末期结清金额可低于此限制。` : '尚未录入合同最低还款额；目前仅计算可行性，不代表贷款方已批准。'}</p>
   {policy.data.sourceNote && <p className="source-note">合同依据：{policy.data.sourceNote}</p>}
   <p className="source-note">规则由家庭管理员按合同录入，仅约束新生成的后续计划，不改写已有期次。期数按计划日期计数，自定义日期不一定按月。</p>
   {draft ? <fieldset disabled={disabled || save.isPending || policy.isFetching} className="feature-form">
    <label>最低常规每期还款额（可选）<input name="minimumInstallmentAmount" inputMode="decimal" value={draft.minimumInstallmentAmount} onChange={e => edit({ ...draft, minimumInstallmentAmount: e.target.value })} /></label>
    <label>合同依据（可选）<textarea name="sourceNote" maxLength={1000} value={draft.sourceNote} onChange={e => edit({ ...draft, sourceNote: e.target.value })} /></label>
    {conflict && <p role="status">其他管理员已修改规则。读取最新版本后，请核对下方保留的输入再保存。</p>}
    {reloaded && <p role="status">已载入最新版本，您的输入已保留，请核对后保存。</p>}
    <div className="form-footer"><Button onClick={() => { edit(null); save.reset(); setReloaded(false); }}>取消修改规则</Button>{conflict && <Button onClick={() => void reload()}>读取最新规则并保留输入</Button>}<Button disabled={conflict} loading={save.isPending} onClick={() => save.mutate(draft)}>保存合同规则</Button></div>
   </fieldset> : <Button disabled={disabled || policy.isFetching} onClick={() => { save.reset(); edit({ minimumInstallmentAmount: policy.data!.minimumInstallmentAmount ?? '', sourceNote: policy.data!.sourceNote ?? '', revision: policy.data!.revision }); }}>编辑合同规则</Button>}
  </>}
 </details></section>;
}
