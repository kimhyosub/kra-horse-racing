package com.sub.kra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.kra.config.KraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 페이징·재시도·호출수 집계를 담당하는 API 클라이언트 */
@Component
public class KraApiClient {

    private static final Logger log = LoggerFactory.getLogger(KraApiClient.class);
    private final KraProperties props;
    private final RestTemplate rest = new RestTemplate();
    private final ResponseParser parser;
    private int calls = 0;

    public KraApiClient(KraProperties props, ObjectMapper om) {
        this.props = props;
        this.parser = new ResponseParser(om);
    }

    public int getCalls() { return calls; }

    /** 전체 페이지를 받아 item 목록 반환 */
    public List<JsonNode> fetchAll(String url, Map<String, String> params) {
        List<JsonNode> all = new ArrayList<JsonNode>();
        int page = 1;
        while (true) {
            Map<String, String> p = new LinkedHashMap<String, String>(params);
            p.put("pageNo", String.valueOf(page));
            p.put("numOfRows", String.valueOf(props.getNumOfRows()));
            ResponseParser.Page res = fetchPage(url, p);
            all.addAll(res.items);
            if (all.size() >= res.totalCount) break;
            // 다 받기 전에 빈 페이지가 오면(서버 장애 등) 일부만 받은 것 → 저장하지 않도록 실패로 처리
            if (res.items.isEmpty())
                throw new KraApiException("PARTIAL", "총 " + res.totalCount + "건 중 " + all.size() + "건만 받음 (" + page + "쪽이 비어 있음)");
            page++;
        }
        return all;
    }

    private ResponseParser.Page fetchPage(String url, Map<String, String> params) {
        URI uri = buildUri(url, params);
        for (int attempt = 1; ; attempt++) {
            if (calls >= props.getDailyLimit())
                throw new KraApiException("LIMIT", "설정한 일일 호출 한도(" + props.getDailyLimit() + ") 도달");
            sleep(props.getRequestDelayMs());
            calls++;
            try {
                // 응답에 charset 이 없으면 RestTemplate 이 ISO-8859-1 로 읽어 한글이 깨지므로 바이트로 받아 UTF-8 로 푼다
                byte[] raw = rest.getForObject(uri, byte[].class);
                String body = raw == null ? "" : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                return parser.parse(body);
            } catch (KraApiException e) {
                if (e.isFatal() || attempt >= props.getMaxRetries()) throw e;
                log.warn("API 오류 {} → 재시도 {}/{}", e.getMessage(), attempt, props.getMaxRetries());
                sleep(2000L * attempt); // 23(초당 한도) 등은 잠시 후 재시도
            } catch (RestClientException e) {
                if (attempt >= props.getMaxRetries()) throw new KraApiException("HTTP", mask(e.getMessage()));
                log.warn("HTTP 오류 {} → 재시도 {}/{}", mask(e.getMessage()), attempt, props.getMaxRetries());
                sleep(3000L * attempt);
            }
        }
    }

    /** 서비스키 이중 인코딩 방지: '%' 가 있으면 이미 인코딩된 키로 보고 그대로 사용 */
    URI buildUri(String url, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        String key = props.getServiceKey() == null ? "" : props.getServiceKey().trim();
        sb.append("serviceKey=").append(key.contains("%") ? key : enc(key));
        for (Map.Entry<String, String> e : params.entrySet())
            sb.append('&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        return URI.create(sb.toString());
    }

    /** 오류 메시지에 요청 URL이 들어가면 인증키가 로그에 남으므로 가린다 */
    static String mask(String s) {
        return s == null ? null : s.replaceAll("(?i)(serviceKey=)[^&\"\\s]+", "$1***");
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
