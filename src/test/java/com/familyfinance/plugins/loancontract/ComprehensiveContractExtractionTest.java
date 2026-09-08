package com.familyfinance.plugins.loancontract;

import com.familyfinance.loan.LoanType;
import com.familyfinance.loan.RepaymentMethod;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖真实贷款合同的各种排版句式，并把“识别率 ≥ 90%”写成可执行断言。
 * 测试1精确复刻《个人住房按揭贷款合同》模拟样例的段落结构与干扰文本（月供行、抵押评估价、页码等），
 * 测试2对一批真实常见的句式变体统计目标字段的识别命中率。
 */
class ComprehensiveContractExtractionTest {

    /** 复刻真实模拟样例：本金出现在“借款人民币（大写）…”句式中，无显式“贷款本金：”标签；含月供/抵押价等干扰。 */
    private static final String MORTGAGE_SAMPLE = String.join("\n",
            "个人住房按揭贷款合同",
            "借款人（简称甲方）：朱涵乐 身份证号：330106199005120018 甲方住址：浙江省杭州市西湖区文三路100号",
            "甲方为购买自住住房需要，特向乙方申请个人住房按揭贷款，经乙方审查同意发放。",
            "1.甲方向乙方借款人民币（大写）壹佰贰拾万元整（¥1,200,000.00元），规定用于购买坐落于浙江省杭州市西湖区文三路100号的自住住房。",
            "2.借款期限约定为25年（共300个月），即从2026年09月08日起至2051年09月08日止。贷款发放日为2026年09月08日，实际放款金额以借款借据为准。",
            "3.贷款利息，自支用贷款之日起，以支用额按年利率4.20%（折合月利率0.35%）计算，按月结息。",
            "4.甲方保证按还款计划归还贷款本金。还款方式为：等额本息还款法。",
            "甲方每月偿还贷款本息合计约人民币 6,401.65 元（具体以乙方实际出账账单为准），共分 300 期偿还。",
            "10.本合同项下抵押住房经评估价值为人民币2,000,000.00元，贷款抵押率为60%。",
            "签订日期：2026年09月08日");

    @Test
    void extractsEveryFieldFromRealisticMortgageSampleText() throws Exception {
        var service = new LoanContractExtractionService();
        var result = service.extract(createDocx(MORTGAGE_SAMPLE));

        assertThat(result.fields().loanType()).isEqualTo(LoanType.MORTGAGE);
        assertThat(result.fields().principal()).isEqualTo("1200000");          // 壹佰贰拾万元整
        assertThat(result.fields().annualRatePercent()).isEqualTo("4.20");
        assertThat(result.fields().termMonths()).isEqualTo(300);               // 25 年
        assertThat(result.fields().startOn()).hasToString("2026-09-08");
        assertThat(result.fields().repaymentMethod()).isEqualTo(RepaymentMethod.EQUAL_PAYMENT);
        assertThat(result.warnings()).isEmpty();
        // 全部 6 个字段均被识别 → 该样例识别率 100%
        assertThat(result.fields()).hasNoNullFieldsOrProperties();
    }

    @Test
    void extractionHitRateIsAboveNinetyPercentAcrossRealisticVariants() throws Exception {
        var service = new LoanContractExtractionService();
        String[] samples = {
                // 1. 标准“贷款本金：”标签 + 千分位
                "个人住房贷款合同\n贷款本金：1,000,000.00元\n年利率：4.5%\n贷款期限：30年（共360个月）\n还款方式：等额本息\n起息日：2026年1月1日",
                // 2. 大写金额带小写括号
                "个人住房贷款合同\n贷款本金：人民币伍拾万元整（小写：500,000.00元）\n年利率：4.2%\n贷款期限：25年（共300个月）\n还款方式：等额本息\n起息日：2025年5月20日",
                // 3. 万元单位
                "个人住房贷款合同\n贷款本金：80万元整\n年利率：4.0%\n贷款期限：20年（共240个月）\n还款方式：等额本息\n起息日：2026年3月1日",
                // 4. “借款金额：”标签 + 大写 + ¥
                "个人住房贷款合同\n借款金额：人民币叁拾万元整（¥300,000.00元）\n年利率：3.9%\n贷款期限：15年（共180个月）\n还款方式：等额本息\n起息日：2025年12月31日",
                // 5. 车贷 + 等额本金
                "个人汽车贷款合同\n贷款本金：200,000.00元\n年利率：5.5%\n贷款期限：36个月\n还款方式：等额本金\n起息日：2026年3月15日",
                // 6. 无千分位金额（防截断回归）
                "个人借款合同\n贷款本金：500000.00元\n年利率：4.2%\n贷款期限：25年（共300个月）\n还款方式：等额本息\n起息日：2026年7月1日",
                // 7. 借款期限用“月”+ 起息日 ISO
                "个人住房贷款合同\n贷款本金：1,500,000.00元\n年利率：4.8%\n借款期限：240个月\n还款方式：等额本息\n起息日：2026-06-01",
                // 8. “贷款金额”标签 + 万元带空格
                "个人借款合同\n贷款金额：120 万元整\n年利率：4.0%\n贷款期限：10年（共120个月）\n还款方式：等额本息\n起息日：2026年2月15日",
        };

        int required = 6; // 目标字段：本金/年利率/期限/起息日/还款方式/类型
        int correct = 0;
        for (String sample : samples) {
            var f = service.extract(createDocx(sample)).fields();
            boolean allPresent = f.principal() != null && f.annualRatePercent() != null
                    && f.termMonths() != null && f.startOn() != null
                    && f.repaymentMethod() != null && f.loanType() != null;
            if (allPresent) correct++;
        }
        // 8 份变体全部命中 → 100%；低于 90%（约 <7/8）则失败
        assertThat(correct).isGreaterThanOrEqualTo((int) Math.ceil(samples.length * 0.9));
    }

    private static MockMultipartFile createDocx(String content) throws Exception {
        byte[] document;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText(content);
            word.write(output);
            document = output.toByteArray();
        }
        return new MockMultipartFile("file", "contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", document);
    }
}
