package com.sub.kra.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.sub.kra.client.KraApiClient;
import com.sub.kra.client.KraApiException;
import com.sub.kra.config.KraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 엔드포인트/파라미터 조합을 자동으로 시험해서 실제로 데이터가 나오는 조합을 찾음.
 * 사용: --kra.mode=probe [--kra.probe-date=20260830]
 */
@Service
public class ProbeService {

    private static final Logger log = LoggerFactory.getLogger(ProbeService.class);
    private final KraProperties props;
    private final KraApiClient client;

    public ProbeService(KraProperties props, KraApiClient client) { this.props = props; this.client = client; }

    public void run() {
        List<String> urls = props.getProbeUrls();
        List<String> dateParams = Arrays.asList("rc_date", "rcDate");
        log.info("엔드포인트 탐색 시작: 날짜 {}, 경마장 {}", props.getProbeDate(), props.getMeets().get(0));
        String found = null;
        for (String url : urls) {
            for (String dp : dateParams) {
                Map<String, String> p = new LinkedHashMap<String, String>();
                p.put("_type", "json");
                p.put("meet", String.valueOf(props.getMeets().get(0)));
                p.put(dp, props.getProbeDate());
                try {
                    List<JsonNode> items = client.fetchAll(url, p);
                    if (items.isEmpty()) { log.info("  [빈 응답] {} ({}=)", url, dp); continue; }
                    List<String> keys = new ArrayList<String>();
                    Iterator<String> it = items.get(0).fieldNames();
                    while (it.hasNext()) keys.add(it.next());
                    log.info("  [성공] {} ({}=) → {}행, 필드 {}개: {}", url, dp, items.size(), keys.size(), keys);
                    if (found == null) found = url + " | date-param: " + dp;
                } catch (KraApiException e) {
                    log.info("  [오류] {} ({}=) → {}", url, dp, e.getMessage());
                    if ("22".equals(e.getCode()) || "30".equals(e.getCode()) || "31".equals(e.getCode()) || "32".equals(e.getCode())) {
                        log.warn("키/한도 문제로 탐색 중단: {}", e.getMessage());
                        return;
                    }
                }
            }
        }
        if (found != null) log.info("==> application.yml 의 kra.apis.race-result 에 설정하세요: {}", found);
        else log.warn("==> 데이터가 나온 조합이 없습니다. 공공데이터포털 '미리보기'의 요청 URL을 확인해 kra.probe-urls 에 추가하세요.");
        log.info("탐색에 사용한 호출: {}건", client.getCalls());
    }
}
