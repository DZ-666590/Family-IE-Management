package com.familyfinance.accounting;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Release gate: the unfinished cross-currency reporting must never be enabled implicitly. */
@Component
public class MultiCurrencyPolicy {
    private final boolean enabled;
    private final DeploymentRevisionGate deployment;
    public MultiCurrencyPolicy(@Value("${app.multicurrency.enabled:false}") boolean enabled,DeploymentRevisionGate deployment){this.enabled=enabled;this.deployment=deployment;}
    public List<String> currencies(){return enabled&&deployment.ready()?List.of("CNY","HKD","USD"):List.of("CNY");}
    public void requireEnabled(){if(!enabled||!deployment.ready())throw new com.familyfinance.shared.ResourceConflictException("MULTICURRENCY_DISABLED","多币种记账尚未启用或新版本仍在部署验证中");}
    public void validate(String currency,Map<String,String> fields){
        var allowed=currencies();
        if(!allowed.contains(currency))fields.put("currency",allowed.size()>1?"币种只能是 CNY、HKD 或 USD":"当前仅支持人民币账户，多币种未启用或新版本仍在部署验证中");
    }
}
