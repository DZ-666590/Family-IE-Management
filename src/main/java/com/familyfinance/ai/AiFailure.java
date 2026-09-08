package com.familyfinance.ai;

/** Only fixed, non-sensitive messages/codes; never attach upstream exceptions. */
public final class AiFailure extends RuntimeException {
    final int status;
    final String code;
    AiFailure(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }
    public String code() { return code; }
    public int status() { return status; }
    static AiFailure unavailable() { return new AiFailure(503, "AI_STORAGE_UNAVAILABLE", "AI 加密存储尚未就绪，请联系管理员"); }
    static AiFailure invalid() { return new AiFailure(400, "AI_INVALID_SETTINGS", "请检查 API 地址、模型及密钥格式"); }
}
