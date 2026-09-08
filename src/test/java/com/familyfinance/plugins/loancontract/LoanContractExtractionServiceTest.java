package com.familyfinance.plugins.loancontract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class LoanContractExtractionServiceTest {
    @Test
    void extractsCommonLoanFieldsFromDocxWithoutReturningContractText() throws Exception {
        byte[] document;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText(
                    "个人住房贷款合同\n贷款本金：1,280,000.00元\n年利率：4.25%\n贷款期限：30年\n还款方式：等额本息\n起息日：2026年9月8日");
            word.write(output);
            document = output.toByteArray();
        }

        LoanContractExtractionResponse result = new LoanContractExtractionService().extract(
                new MockMultipartFile("file", "住房贷款合同.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", document));

        assertThat(result.fields().suggestedName()).isEqualTo("住房贷款合同");
        assertThat(result.fields().loanType()).isEqualTo(com.familyfinance.loan.LoanType.MORTGAGE);
        assertThat(result.fields().principal()).isEqualTo("1280000.00");
        assertThat(result.fields().annualRatePercent()).isEqualTo("4.25");
        assertThat(result.fields().termMonths()).isEqualTo(360);
        assertThat(result.fields().repaymentMethod()).isEqualTo(com.familyfinance.loan.RepaymentMethod.EQUAL_PAYMENT);
        assertThat(result.fields().startOn()).hasToString("2026-09-08");
        assertThat(result.documentName()).isEqualTo("住房贷款合同.docx");
        assertThat(result.warnings()).doesNotContain("未识别到贷款本金，请人工填写");
    }

    @Test
    void extractsPrincipalWrittenWithoutThousandSeparators() throws Exception {
        var service = new LoanContractExtractionService();
        assertThat(principalOf(service, "个人借款合同\n贷款本金：人民币500000.00元\n年利率：3.85%")).isEqualTo("500000.00");
        assertThat(principalOf(service, "个人借款合同\n贷款本金：128000元\n年利率：3.85%")).isEqualTo("128000");
    }

    @Test
    void extractsLoanAmountLabelAndScalesTenThousandUnitPrincipal() throws Exception {
        var service = new LoanContractExtractionService();
        assertThat(principalOf(service, "个人借款合同\n贷款金额：50 万元整\n年利率：4.9%")).isEqualTo("500000");
    }

    @Test
    void doesNotMistakeMonthlyPaymentForPrincipal() throws Exception {
        var service = new LoanContractExtractionService();
        assertThat(principalOf(service,
                "个人住房贷款合同\n贷款本金：人民币伍拾万元整\n每月偿还贷款本息：人民币5,300.00元\n年利率：4.25%")).isEqualTo("500000");
        assertThat(principalOf(service,
                "个人借款合同\n贷款本金：500,000.00元\n每月偿还贷款本息：5,300.00元\n年利率：4.25%")).isEqualTo("500000.00");
        assertThat(principalOf(service,
                "个人借款合同\n贷款本金：伍拾万元整，分300期\n还款方式：等额本息")).isEqualTo("500000");
    }

    @Test
    void extractsPrincipalFromBorrowAmountClauseWithoutExplicitLabel() throws Exception {
        var service = new LoanContractExtractionService();
        String contract = "个人住房按揭贷款合同\n"
                + "1.甲方向乙方借款人民币（大写）壹佰贰拾万元整（¥1,200,000.00元），规定用于购买自住住房。\n"
                + "4.甲方保证按还款计划归还贷款本金。还款方式为：等额本息还款法。\n"
                + "甲方每月偿还贷款本息合计约人民币 6,401.65 元（具体以乙方实际出账账单为准），共分 300 期偿还。\n"
                + "10.本合同项下抵押住房经评估价值为人民币2,000,000.00元，贷款抵押率为60%。";
        assertThat(principalOf(service, contract)).isEqualTo("1200000");
    }

    private static String principalOf(LoanContractExtractionService service, String content) throws Exception {
        byte[] document;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText(content);
            word.write(output);
            document = output.toByteArray();
        }
        return service.extract(new MockMultipartFile("file", "借款合同.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", document)).fields().principal();
    }

    @Test
    void rejectsUnsupportedFiles() {
        var service = new LoanContractExtractionService();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.extract(
                new MockMultipartFile("file", "contract.txt", "text/plain", "贷款本金:100".getBytes())))
                .isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
    }
}
