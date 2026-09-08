package com.familyfinance.plugins.loancontract;

import java.io.IOException;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.web.multipart.MultipartFile;

final class LoanContractTextExtractor {
    private static final long MAX_BYTES = 10 * 1024 * 1024;

    String extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("file", "请上传 Word 或 PDF 合同文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw invalid("file", "合同文件不能超过 10MB");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            String text;
            if (name.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(file.getContentType())) {
                text = pdf(file.getBytes());
            } else if (name.endsWith(".docx")
                    || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(file.getContentType())) {
                text = docx(file.getBytes());
            } else {
                throw invalid("file", "仅支持 .docx 和 .pdf 文件");
            }
            if (text == null || text.isBlank()) {
                throw invalid("file", "合同中未提取到可识别文本；扫描件请先进行 OCR");
            }
            return text.length() > 200_000 ? text.substring(0, 200_000) : text;
        } catch (LoanContractExtractionException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("file", "合同文件无法解析，请确认文件未损坏且包含文本层");
        }
    }

    private static String pdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String docx(byte[] bytes) throws IOException {
        StringBuilder text = new StringBuilder();
        try (var document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append('\n');
            }
            for (XWPFTable table : document.getTables()) {
                for (var row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        text.append(cell.getText()).append('\t');
                    }
                    text.append('\n');
                }
            }
        }
        return text.toString();
    }

    private static LoanContractExtractionException invalid(String field, String message) {
        return new LoanContractExtractionException(field, message);
    }

    static final class LoanContractExtractionException extends RuntimeException {
        private final String field;
        LoanContractExtractionException(String field, String message) {
            super(message);
            this.field = field;
        }
        String field() { return field; }
    }
}
