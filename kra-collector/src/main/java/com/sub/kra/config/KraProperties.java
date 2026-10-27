package com.sub.kra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.util.*;

/** application.yml 의 kra.* 설정 */
@ConfigurationProperties(prefix = "kra")
public class KraProperties {

    /** 공공데이터포털 서비스키 (Encoding/Decoding 키 모두 가능) */
    private String serviceKey;
    /** 실행 모드: probe(엔드포인트 탐색) | collect(수집) | export(CSV 내보내기) | all(수집 후 내보내기) */
    private String mode = "all";
    /** 경마장 번호 목록: 1=서울, 2=제주, 3=부경, 4=영천 */
    private List<Integer> meets = new ArrayList<Integer>(Collections.singletonList(1));
    /** 수집 기간 (yyyy-MM-dd) */
    private String from;
    private String to;
    /** 수집할 요일. 비우면 모든 날짜 (공휴일 경마 누락 방지) */
    private List<DayOfWeek> weekdays = new ArrayList<DayOfWeek>();
    /** 저장 폴더 */
    private String outputDir = "./kra-data";
    /** 하루 호출 한도 (개발계정 3,000건 → 여유 두고 2,800) */
    private int dailyLimit = 2800;
    /** 호출 간 대기(ms) */
    private long requestDelayMs = 250;
    private int numOfRows = 1000;
    private int maxRetries = 3;
    /** 이미 받은 날짜는 건너뜀 (재실행 시 이어받기) */
    private boolean skipExisting = true;
    /** probe 모드: 시험할 URL 후보와 날짜 */
    private List<String> probeUrls = new ArrayList<String>();
    private String probeDate = "20260830";
    /** 수집 대상 API 정의 (이름 → 정의) */
    private Map<String, ApiDef> apis = new LinkedHashMap<String, ApiDef>();

    public static class ApiDef {
        private boolean enabled = true;
        /** 요청 URL (예: https://apis.data.go.kr/B551015/API214_1/RaceDetailResult_1) */
        private String url;
        /** 날짜 파라미터 이름과 형식 */
        private String dateParam = "rc_date";
        private String dateFormat = "yyyyMMdd";
        /** 경마장 파라미터 이름 */
        private String meetParam = "meet";
        /** 이 API만 이어받기 여부를 따로 지정 (null 이면 전역 skip-existing 사용). 출전표는 false 권장 */
        private Boolean skipExisting;
        /** 추가 고정 파라미터 (예: _type=json) */
        private Map<String, String> extraParams = new LinkedHashMap<String, String>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getDateParam() { return dateParam; }
        public void setDateParam(String dateParam) { this.dateParam = dateParam; }
        public String getDateFormat() { return dateFormat; }
        public void setDateFormat(String dateFormat) { this.dateFormat = dateFormat; }
        public String getMeetParam() { return meetParam; }
        public void setMeetParam(String meetParam) { this.meetParam = meetParam; }
        public Boolean getSkipExisting() { return skipExisting; }
        public void setSkipExisting(Boolean skipExisting) { this.skipExisting = skipExisting; }
        public Map<String, String> getExtraParams() { return extraParams; }
        public void setExtraParams(Map<String, String> extraParams) { this.extraParams = extraParams; }
    }

    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String serviceKey) { this.serviceKey = serviceKey; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public List<Integer> getMeets() { return meets; }
    public void setMeets(List<Integer> meets) { this.meets = meets; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public List<DayOfWeek> getWeekdays() { return weekdays; }
    public void setWeekdays(List<DayOfWeek> weekdays) { this.weekdays = weekdays; }
    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int dailyLimit) { this.dailyLimit = dailyLimit; }
    public long getRequestDelayMs() { return requestDelayMs; }
    public void setRequestDelayMs(long requestDelayMs) { this.requestDelayMs = requestDelayMs; }
    public int getNumOfRows() { return numOfRows; }
    public void setNumOfRows(int numOfRows) { this.numOfRows = numOfRows; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public boolean isSkipExisting() { return skipExisting; }
    public void setSkipExisting(boolean skipExisting) { this.skipExisting = skipExisting; }
    public List<String> getProbeUrls() { return probeUrls; }
    public void setProbeUrls(List<String> probeUrls) { this.probeUrls = probeUrls; }
    public String getProbeDate() { return probeDate; }
    public void setProbeDate(String probeDate) { this.probeDate = probeDate; }
    public Map<String, ApiDef> getApis() { return apis; }
    public void setApis(Map<String, ApiDef> apis) { this.apis = apis; }
}
