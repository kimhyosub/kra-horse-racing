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
            if (res.items.isEmpty() || all.size() >= res.totalCount) break;
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
                String body = rest.getForObject(uri, String.class);
                return parser.parse(body);
            } catch (KraApiException e) {
                if (e.isFatal() || attempt >= props.getMaxRetries()) throw e;
                log.warn("API 오류 {} → 재시도 {}/{}", e.getMessage(), attempt, props.getMaxRetries());
                sleep(2000L * attempt); // 23(초당 한도) 등은 잠시 후 재시도
            } catch (RestClientException e) {
                if (attempt >= props.getMaxRetries()) throw new KraApiException("HTTP", e.getMessage());
                log.warn("HTTP 오류 {} → 재시도 {}/{}", e.getMessage(), attempt, props.getMaxRetries());
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

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
