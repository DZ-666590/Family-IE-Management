import {useQuery} from '@tanstack/react-query';
import type {RequestFn} from '../common';
import type {InvestmentTrade} from '../../api/contracts';
export type PlanFrequency='WEEKLY'|'BIWEEKLY'|'MONTHLY';
export interface InvestmentPlan {
 id:number;name:string;accountId:number;accountName:string;fundingAccountId:number;securityId:number;securityName:string;symbol:string;
 currency:string;amount:string;frequency:PlanFrequency;firstDueOn:string;nextDueOn:string|null;assignedUserId:number;state:'ACTIVE'|'PAUSED'|'ENDED';
}
export interface InvestmentPlanOccurrence {
 id:number;planId:number;planName:string;accountId:number;accountName:string;fundingAccountId:number;securityId:number;securityName:string;symbol:string;
 currency:string;amount:string;dueOn:string;state:'PENDING'|'CONFIRMED'|'SKIPPED';remindAt:string|null;tradeId:number|null;actualAmount:string|null;reason:string|null;
 tradeReversed?:boolean;currentTrade?:InvestmentTrade|null;
}
export interface InvestmentPlansResult {plans:InvestmentPlan[];occurrences:InvestmentPlanOccurrence[];pendingCount?:number;hasMorePlans?:boolean;hasMoreOccurrences?:boolean}
export const frequencyLabel:Record<PlanFrequency,string>={WEEKLY:'每周',BIWEEKLY:'每两周',MONTHLY:'每月'};
export function useInvestmentPlans(request:RequestFn,planPage=0,occurrencePage=0){
 return useQuery({queryKey:['investment-plans',planPage,occurrencePage],queryFn:()=>request<InvestmentPlansResult>(`/api/investment-plans${planPage||occurrencePage?`?planPage=${planPage}&occurrencePage=${occurrencePage}&size=20`:''}`),refetchInterval:60000,refetchIntervalInBackground:false,retry:false});
}
