package com.visionary.dto;

/**
 * PPTX 导出结果，携带真实导出模式供 Controller 设置响应头。
 * exportMode: premium | standard | standard-fallback | java-fallback
 */
public record PptxExportResult(byte[] bytes, String exportMode) {

    public static final String MODE_PREMIUM = "premium";
    public static final String MODE_STANDARD = "standard";
    public static final String MODE_STANDARD_FALLBACK = "standard-fallback";
    public static final String MODE_JAVA_FALLBACK = "java-fallback";

    public static PptxExportResult premium(byte[] bytes) {
        return new PptxExportResult(bytes, MODE_PREMIUM);
    }

    public static PptxExportResult standard(byte[] bytes) {
        return new PptxExportResult(bytes, MODE_STANDARD);
    }

    public static PptxExportResult standardFallback(byte[] bytes) {
        return new PptxExportResult(bytes, MODE_STANDARD_FALLBACK);
    }

    public static PptxExportResult javaFallback(byte[] bytes) {
        return new PptxExportResult(bytes, MODE_JAVA_FALLBACK);
    }
}
