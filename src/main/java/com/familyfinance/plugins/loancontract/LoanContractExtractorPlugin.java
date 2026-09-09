package com.familyfinance.plugins.loancontract;

import com.familyfinance.extension.FinancePlugin;
import com.familyfinance.extension.PluginDescriptor;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.RequestValidationException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/plugins/loan-contract-extractor")
@ConditionalOnProperty(name = "app.plugins.loan-contract-extractor.enabled", havingValue = "true", matchIfMissing = false)
public class LoanContractExtractorPlugin implements FinancePlugin {
    private final LoanContractAutoFillService autofill;
    private final FamilyMutationAuthorization authorization;

    public LoanContractExtractorPlugin(LoanContractAutoFillService autofill, FamilyMutationAuthorization authorization) {
        this.autofill = autofill;
        this.authorization = authorization;
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                "loan-contract-extractor", "1.0.0", 1, "贷款合同智能提取",
                "从 Word/PDF 合同中提取贷款信息并预填新建贷款表单",
                "/workspace/extensions/loan-contract-extractor", List.of("loan.read", "loan.extract"));
    }

    @PostMapping("/extract")
    public ApiEnvelope<LoanContractExtractionResponse> extract(
            Authentication authentication,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "useAi", defaultValue = "false") boolean useAi) {
        authorization.requireAdmin(authentication);
        try {
            return ApiEnvelope.data(autofill.extract(authentication, useAi, file));
        } catch (RequestValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RequestValidationException(java.util.Map.of(
                    "file", "合同文件无法识别，请上传包含文本层的有效 Word/PDF 文件"));
        }
    }
}
