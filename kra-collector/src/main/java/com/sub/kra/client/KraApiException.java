package com.sub.kra.client;

/** API 오류. code 는 공공데이터포털 resultCode (예: 22=일일한도초과, 30=미등록키) */
public class KraApiException extends RuntimeException {
    private final String code;
    public KraApiException(String code, String message) { super("[" + code + "] " + message); this.code = code; }
    public String getCode() { return code; }
    /** 더 진행해도 소용없는 오류 (한도 초과, 키 문제) */
    public boolean isFatal() {
        return "22".equals(code) || "20".equals(code) || "30".equals(code) || "31".equals(code) || "32".equals(code);
    }
}
