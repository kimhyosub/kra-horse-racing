package com.sub.kra.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.sub.kra.client.KraApiClient;
import com.sub.kra.client.KraApiException;
import com.sub.kra.config.KraProperties;
import com.sub.kra.store.RawStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 기간 × 경마장 × API 를 돌며 날짜별 원본 저장. 한도 도달 시 중단하고 다음 실행에서 이어받음. */
@Service
public class CollectService {

    private static final Logger log = LoggerFactory.getLogger(CollectService.class);
    private final KraProperties props;
    private final KraApiClient client;
    private final RawStore store;

    public CollectService(KraProperties props, KraApiClient client, RawStore store) {
        this.props = props; this.client = client; this.store = store;
    }

    public static class Summary {
        public int saved, skipped, emptyDays, rows, failed;
        public boolean stoppedByLimit;
        public String stoppedAt;
        @Override public String toString() {
            return String.format("저장 %d일(%d행, 빈 날 %d) / 건너뜀 %d / 실패 %d%s",
                    saved, rows, emptyDays, skipped, failed, stoppedByLimit ? " / 한도로 중단: " + stoppedAt : "");
        }
    }

    public Summary run() {
        if (props.getServiceKey() == null || props.getServiceKey().trim().isEmpty())
            throw new IllegalStateException("kra.service-key 가 비어 있습니다.");
        LocalDate from = LocalDate.parse(props.getFrom()), to = LocalDate.parse(props.getTo());
        Summary s = new Summary();
        outer:
        for (Map.Entry<String, KraProperties.ApiDef> e : props.getApis().entrySet()) {
            String api = e.getKey();
            KraProperties.ApiDef def = e.getValue();
            if (!def.isEnabled() || def.getUrl() == null || def.getUrl().trim().isEmpty()) {
                log.info("[{}] 비활성 또는 URL 미설정 → 건너뜀", api);
                continue;
            }
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(def.getDateFormat());
            for (Integer meet : props.getMeets()) {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (!props.getWeekdays().isEmpty() && !props.getWeekdays().contains(d.getDayOfWeek())) continue;
                    boolean skip = def.getSkipExisting() != null ? def.getSkipExisting() : props.isSkipExisting();
                    if (skip && store.isFinal(api, meet, d)) { s.skipped++; continue; }
                    Map<String, String> p = new LinkedHashMap<String, String>(def.getExtraParams());
                    p.put(def.getMeetParam(), String.valueOf(meet));
                    p.put(def.getDateParam(), d.format(fmt));
                    try {
                        List<JsonNode> items = client.fetchAll(def.getUrl(), p);
                        // 오늘·미래 날짜의 빈 응답은 저장하지 않음 (결과가 나온 뒤 다시 받을 수 있도록)
                        if (items.isEmpty() && !d.isBefore(LocalDate.now())) { s.emptyDays++; continue; }
                        store.save(api, meet, d, items);
                        s.saved++; s.rows += items.size();
                        if (items.isEmpty()) s.emptyDays++;
                        else log.info("[{}] meet{} {} → {}행 (누적 호출 {})", api, meet, d, items.size(), client.getCalls());
                    } catch (KraApiException ex) {
                        if (ex.isFatal() || "LIMIT".equals(ex.getCode())) {
                            s.stoppedByLimit = true; s.stoppedAt = api + " meet" + meet + " " + d + " (" + ex.getMessage() + ")";
                            log.warn("중단: {}. 같은 명령으로 다시 실행하면 이어서 받습니다.", ex.getMessage());
                            break outer;
                        }
                        s.failed++;
                        log.error("[{}] meet{} {} 실패: {}", api, meet, d, ex.getMessage());
                    } catch (IOException ex) {
                        s.failed++;
                        log.error("[{}] 저장 실패 {}: {}", api, d, ex.getMessage());
                    }
                }
            }
        }
        log.info("수집 완료: {} (이번 실행 호출 {}건)", s, client.getCalls());
        return s;
    }
}
