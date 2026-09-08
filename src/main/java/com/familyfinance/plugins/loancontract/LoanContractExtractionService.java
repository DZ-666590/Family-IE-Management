package com.familyfinance.plugins.loancontract;

import com.familyfinance.loan.LoanType;
import com.familyfinance.loan.RepaymentMethod;
import com.familyfinance.shared.RequestValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LoanContractExtractionService {
    private static final Pattern MONEY = Pattern.compile("(?<!\\d)((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)(?:\\s*元)?");
    private static final Pattern RATE = Pattern.compile("(?<!\\d)(\\d{1,3}(?:\\.\\d{1,4})?)\\s*%?");
    private static final Pattern MONTHS = Pattern.compile("(?<!\\d)(\\d{1,3})\\s*(?:个月|月|期|年)");
    private static final Pattern DATE = Pattern.compile("(20\\d{2})\\s*[年./-]\\s*(\\d{1,2})\\s*[月./-]\\s*(\\d{1,2})\\s*日?");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    /** Stops the value search so a neighbouring field (e.g. 每月偿还贷款本息) is never read as the principal. */
    private static final Pattern VALUE_BOUNDARY = Pattern.compile(
            "月偿还|月归还|月还本付息|月供|月还款|每月应还|还款方式|偿还方式|还款计划|还款日期|还款期限|首次还款日|还款日"
                    + "|年利率|月利率|执行利率|贷款利率|借款利率|贷款期限|借款期限|分期期数|还款期数"
                    + "|起息日|放款日|放款日期|借款日期|贷款发放日|合同生效日");
    /** Chinese upper-case money numerals only; simplified 十/百/千 are excluded to avoid false readings like 十万元. */
    private static final String UPPER_DIGITS = "零壹贰叁肆伍陆柒捌玖";
    private static final Pattern UPPER_MONEY = Pattern.compile("([零壹贰叁肆伍陆柒捌玖拾佰仟万亿]{1,20})\\s*[元圆]");
    private static final Pattern UPPER_JIAO = Pattern.compile("([零壹贰叁肆伍陆柒捌玖])\\s*角");
    private static final Pattern UPPER_FEN = Pattern.compile("([零壹贰叁肆伍陆柒捌玖])\\s*分");
    /** Chars that may sit between a label and its value (人民币／（大写）／：/ upper numerals…); anything else stops the search. */
    private static final Pattern GAP = Pattern.compile(
            "[人民大写小写币（）()：:，,\\s整正共计为即约零壹贰叁肆伍陆柒捌玖拾佰仟万亿万元圆]*+");
    private final LoanContractTextExtractor textExtractor = new LoanContractTextExtractor();

    public LoanContractExtractionResponse extract(MultipartFile file) {
        String text;
        try {
            text = textExtractor.extract(file);
        } catch (LoanContractTextExtractor.LoanContractExtractionException exception) {
            throw new RequestValidationException(Map.of(exception.field(), exception.getMessage()));
        }
        String normalized = text.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ");
        List<String> warnings = new ArrayList<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        LoanType type = type(normalized);
        String principal = principalValue(normalized);
        String rate = valueAfterLabel(normalized, "年利率|贷款利率|执行利率", RATE);
        Integer term = term(normalized);
        LocalDate startOn = date(normalized);
        RepaymentMethod method = method(normalized);
        String name = suggestedName(file.getOriginalFilename(), type);
        confidence.put("loanType", type == null ? 0.0 : 0.95);
        confidence.put("principal", principal == null ? 0.0 : 0.9);
        confidence.put("annualRatePercent", rate == null ? 0.0 : 0.85);
        confidence.put("termMonths", term == null ? 0.0 : 0.85);
        confidence.put("startOn", startOn == null ? 0.0 : 0.8);
        confidence.put("repaymentMethod", method == null ? 0.0 : 0.85);
        if (principal == null) warnings.add("未识别到贷款本金，请人工填写");
        if (rate == null) warnings.add("未识别到年利率，请人工填写");
        if (term == null) warnings.add("未识别到贷款期限，请人工填写");
        if (startOn == null) warnings.add("未识别到起息日，请人工填写");
        return new LoanContractExtractionResponse(file.getOriginalFilename(),
                new LoanContractFields(name, type, principal, rate, term, method, startOn), confidence, warnings);
    }

    private static LoanType type(String text) {
        if (text.contains("房贷") || text.contains("住房贷款") || text.contains("个人住房")) return LoanType.MORTGAGE;
        if (text.contains("车贷") || text.contains("汽车贷款") || text.contains("车辆贷款")) return LoanType.CAR;
        return text.contains("贷款") || text.contains("借款") ? LoanType.OTHER : null;
    }

    private static RepaymentMethod method(String text) {
        if (text.contains("等额本息")) return RepaymentMethod.EQUAL_PAYMENT;
        if (text.contains("等额本金")) return RepaymentMethod.EQUAL_PRINCIPAL;
        return text.contains("还款计划") ? RepaymentMethod.CUSTOM : null;
    }

    private static Integer term(String text) {
        Matcher matcher = MONTHS.matcher(afterAnyLabel(text, "贷款期限|借款期限|还款期限|期限"));
        if (!matcher.find()) return null;
        int value = Integer.parseInt(matcher.group(1));
        String match = matcher.group();
        return match.contains("年") ? Math.multiplyExact(value, 12) : value;
    }

    private static LocalDate date(String text) {
        String value = afterAnyLabel(text, "起息日|放款日|借款日|贷款发放日|合同生效日");
        Matcher matcher = DATE.matcher(value);
        if (matcher.find()) {
            try { return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))); }
            catch (DateTimeException exception) { return null; }
        }
        Matcher iso = Pattern.compile("20\\d{2}-\\d{2}-\\d{2}").matcher(value);
        if (iso.find()) try { return LocalDate.parse(iso.group(), ISO_DATE); } catch (DateTimeParseException ignored) { }
        return null;
    }

    private static String valueAfterLabel(String text, String labels, Pattern valuePattern) {
        Matcher label = Pattern.compile("(?:" + labels + ")\\s*[:：]?\\s*").matcher(text);
        if (!label.find()) return null;
        String window = text.substring(label.end(), Math.min(text.length(), label.end() + 80));
        if (valuePattern == MONEY) return moneyValue(window);
        Matcher value = valuePattern.matcher(window);
        if (!value.find()) return null;
        return value.group(1).replace(",", "");
    }

    /** Principal candidates: labelled fields first, then the 借款人民币（大写）…（¥…元） clause used when no label exists. */
    private static String principalValue(String text) {
        Matcher label = Pattern.compile("(?:贷款本金|贷款金额|借款金额|借款本金|合同金额)\\s*[:：]?\\s*").matcher(text);
        while (label.find()) {
            String value = moneyValue(text.substring(label.end(), Math.min(text.length(), label.end() + 80)));
            if (value != null) return value;
        }
        Matcher upper = Pattern.compile("人民币\\s*[（(]?\\s*大写\\s*[:：)）]?").matcher(text);
        while (upper.find()) {
            String value = moneyValue(text.substring(upper.end(), Math.min(text.length(), upper.end() + 80)));
            if (value != null) return value;
        }
        return null;
    }

    /** Reads the money value at the very start of the (already boundary-cut) window; never scans past unrelated text. */
    private static String moneyValue(String window) {
        Matcher boundary = VALUE_BOUNDARY.matcher(window);
        if (boundary.find()) window = window.substring(0, boundary.start());
        String upper = upperMoney(window);
        if (upper != null) return upper;
        Matcher gap = GAP.matcher(window);
        if (!gap.lookingAt()) return null;
        String rest = window.substring(gap.end());
        Matcher value = MONEY.matcher(rest);
        if (!value.lookingAt()) return null;
        String number = value.group(1).replace(",", "");
        if (rest.substring(value.end()).replace(" ", "").startsWith("万元")) {
            return new BigDecimal(number).movePointRight(4).toPlainString();
        }
        return number;
    }

    /** Reads a Chinese upper-case money amount such as 伍拾万元整; returns null when none is present. */
    private static String upperMoney(String window) {
        Matcher matcher = UPPER_MONEY.matcher(window);
        if (!matcher.find()) return null;
        BigDecimal value = upperNumber(matcher.group(1));
        if (value == null) return null;
        String tail = window.substring(matcher.end(), Math.min(window.length(), matcher.end() + 12));
        Matcher jiao = UPPER_JIAO.matcher(tail);
        if (jiao.find()) value = value.add(BigDecimal.valueOf(UPPER_DIGITS.indexOf(jiao.group(1))).movePointLeft(1));
        Matcher fen = UPPER_FEN.matcher(tail);
        if (fen.find()) value = value.add(BigDecimal.valueOf(UPPER_DIGITS.indexOf(fen.group(1))).movePointLeft(2));
        return value.signum() > 0 ? value.toPlainString() : null;
    }

    private static BigDecimal upperNumber(String numerals) {
        long yi = 0, wan = 0, low = 0, current = 0;
        boolean seenWan = false, hasValue = false;
        for (char c : numerals.toCharArray()) {
            int digit = UPPER_DIGITS.indexOf(c);
            if (digit >= 0) { current = digit; hasValue = true; continue; }
            long unit = switch (c) {
                case '拾' -> 10; case '佰' -> 100; case '仟' -> 1000;
                case '万' -> 10_000; case '亿' -> 100_000_000; default -> -1; };
            if (unit < 0) return null;
            hasValue = true;
            if (unit == 10_000) { wan = (wan + current) * unit; current = 0; seenWan = true; }
            else if (unit == 100_000_000) { yi = (yi + wan + current) * unit; wan = 0; current = 0; seenWan = false; }
            else if (seenWan) { low += (current == 0 ? 1 : current) * unit; current = 0; }
            else { wan += (current == 0 ? 1 : current) * unit; current = 0; }
        }
        long result = yi + wan + low + current;
        return hasValue && result > 0 ? BigDecimal.valueOf(result) : null;
    }

    private static String afterAnyLabel(String text, String labels) {
        Matcher label = Pattern.compile("(?:" + labels + ")\\s*[:：]?\\s*").matcher(text);
        return label.find() ? text.substring(label.end(), Math.min(text.length(), label.end() + 120)) : "";
    }

    private static String suggestedName(String filename, LoanType type) {
        String stem = filename == null ? "" : filename.replaceFirst("(?i)\\.(pdf|docx)$", "").trim();
        if (!stem.isBlank()) return stem.length() > 100 ? stem.substring(0, 100) : stem;
        return type == LoanType.MORTGAGE ? "房贷合同" : type == LoanType.CAR ? "车贷合同" : "贷款合同";
    }
}
