package com.familyfinance.accounting;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Release gate: the unfinished cross-currency reporting must never be enabled implicitly. */
@Component
public class MultiCurrencyPolicy {
    private final boolean enabled;
    public MultiCurrencyPolicy(@Value("${app.multicurrency.enabled:false}") boolean enabled){this.enabled=enabled;}
    public List<String> currencies(){return enabled?List.of("CNY","HKD","USD"):List.of("CNY");}
    public void requireEnabled(){if(!enabled)throw new com.familyfinance.shared.ResourceConflictException("MULTICURRENCY_DISABLED","多币种记账尚未启用");}
    public void validate(String currency,Map<String,String> fields){
        if(!currencies().contains(currency))fields.put("currency",enabled?"币种只能是 CNY、HKD 或 USD":"当前仅支持人民币账户，多币种记账尚未启用");
    }
}
