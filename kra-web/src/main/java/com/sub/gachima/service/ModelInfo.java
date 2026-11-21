package com.sub.gachima.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.gachima.domain.MeetDay;
import com.sub.gachima.domain.Repositories.MeetDayRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * 가중치 화면: 전날 모델과 당일 모델(경마장별)의 요인 가중치를 사람이 읽는 말로.
 * 요인은 경주 안에서 표준화(평균 0, 표준편차 1)하므로 가중치 크기끼리 비교할 수 있다.
 * 예/아니오 요인(첫 출전 등)은 해당할 때 그만큼 더해진다.
 */
@Service
public class ModelInfo {

    /** 요인 → {이름, 값이 클 때 뜻, 작을 때 뜻, 묶음} */
    static final Map<String, String[]> F = new LinkedHashMap<>();
    static {
        F.put("form3", new String[]{"최근 3전 착순", "최근 성적이 좋을수록", "최근 성적이 나쁠수록", "최근 성적"});
        F.put("place_rate", new String[]{"통산 입상률", "입상률이 높을수록", "입상률이 낮을수록", "최근 성적"});
        F.put("early4", new String[]{"초반 위치 (최근 4전)", "뒤에서 달릴수록", "앞에서 달릴수록", "전개"});
        F.put("behind3", new String[]{"1착과 차이 (최근 3전)", "차이가 클수록", "차이가 작을수록", "전개"});
        F.put("best_behind", new String[]{"1착과 최소 차이 (최근 6전)", "차이가 클수록", "차이가 작을수록", "전개"});
        F.put("wet_early", new String[]{"젖은 주로에서 선행력", "젖은 주로에서 앞에서 달릴수록", "젖은 주로에서 뒤에서 달릴수록", "전개"});
        F.put("g1f4", new String[]{"막판 200m 기록 (최근 4전)", "막판이 느릴수록", "막판이 빠를수록", "막판 속도"});
        F.put("dist_place", new String[]{"같은 거리(±100m) 입상률", "이 거리 입상률이 높을수록", "이 거리 입상률이 낮을수록", "거리"});
        F.put("dist_change", new String[]{"거리 변화", "지난번보다 길어질수록", "지난번보다 짧아질수록", "거리"});
        F.put("log_days", new String[]{"지난 경주 뒤 간격", "간격이 길수록", "간격이 짧을수록", "간격·마체중"});
        F.put("first", new String[]{"데뷔전", "데뷔전이면", "", "간격·마체중"});
        F.put("bwd", new String[]{"마체중 증감", "마체중이 늘수록", "마체중이 줄수록", "간격·마체중"});
        F.put("risky_f", new String[]{"마체중 +5kg 이상", "+5kg 이상 늘었으면", "", "간격·마체중"});
        F.put("stable_f", new String[]{"마체중 안정 (±4kg, 35일 이내)", "마체중이 안정적이면", "", "간격·마체중"});
        F.put("jk_rate", new String[]{"기수 1년 승률", "기수 승률이 높을수록", "기수 승률이 낮을수록", "기수·조교사"});
        F.put("tr_rate", new String[]{"조교사 1년 승률", "조교사 승률이 높을수록", "조교사 승률이 낮을수록", "기수·조교사"});
        F.put("jk_change", new String[]{"기수 교체", "기수가 바뀌었으면", "", "기수·조교사"});
        F.put("cls_change", new String[]{"등급 변화", "아래 등급으로 내려왔을수록", "위 등급으로 올라갔을수록", "등급·중량·나이"});
        F.put("tool_change_f", new String[]{"장구 변경", "장구가 바뀌었으면", "", "등급·중량·나이"});
        F.put("budam_change", new String[]{"부담중량 변화", "부담중량이 늘수록", "부담중량이 줄수록", "등급·중량·나이"});
        F.put("wgBudam", new String[]{"부담중량", "부담중량이 무거울수록", "부담중량이 가벼울수록", "등급·중량·나이"});
        F.put("age", new String[]{"나이", "나이가 많을수록", "나이가 어릴수록", "등급·중량·나이"});
        F.put("rating", new String[]{"레이팅", "레이팅이 높을수록", "레이팅이 낮을수록", "등급·중량·나이"});
        F.put("ref_last_f", new String[]{"지난 경주 심판 기록", "지난 경주에 심판 기록이 있으면", "", "심판"});
        F.put("ref_180", new String[]{"최근 6개월 심판 기록 수", "심판 기록이 많을수록", "심판 기록이 적을수록", "심판"});
    }

    public static class Row {
        public String key, name, effect, group;
        public double w, bar;
        public boolean bin, up;
        public String getW() { return String.format("%+.3f", w); }
    }

    public static class Group {
        public final String name; public final double share;
        Group(String name, double share) { this.name = name; this.share = share; }
        public String getPct() { return String.format("%.0f%%", share * 100); }
    }

    public static class Model {
        public String title, note;
        public Double market;            // 당일 모델: 배당(시장) 가중치
        public List<Row> rows = new ArrayList<>();
        public List<Group> groups = new ArrayList<>();
        public String getMarketText() { return market == null ? "" : String.format("%.2f", market); }
    }

    private final MeetDayRepository meetDays;
    private final ObjectMapper om;

    public ModelInfo(MeetDayRepository meetDays, ObjectMapper om) { this.meetDays = meetDays; this.om = om; }

    public Optional<MeetDay> find(String date, Integer meet) {
        if (date != null && meet != null) return meetDays.findByRaceDateAndMeet(date, meet);
        List<MeetDay> all = meetDays.findAllByOrderByRaceDateDescMeetAsc();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** 그날 경마장들 */
    public List<MeetDay> meetsOn(String date) { return meetDays.findByRaceDateOrderByMeet(date); }

    public Model pre(MeetDay md) {
        Model m = new Model();
        m.title = "전날 예상 모델 (경마장 공통)";
        if (md.getPreWeightsJson() == null) { m.note = "이 경주일 파일에는 전날 모델 가중치가 없습니다. export_web.py 로 다시 만들어 주세요."; return m; }
        try {
            JsonNode n = om.readTree(md.getPreWeightsJson());
            List<String> feats = om.convertValue(n.path("feats"), new TypeReference<List<String>>() {});
            Set<String> bin = new HashSet<>(om.convertValue(n.path("bin"), new TypeReference<List<String>>() {}));
            double[] w = om.convertValue(n.path("w"), double[].class);
            m.note = n.path("note").asText("");
            fill(m, feats, bin, w, 0);
        } catch (IOException | IllegalArgumentException e) {
            m.note = "가중치를 읽지 못했습니다: " + e.getMessage();
        }
        return m;
    }

    public Model day(MeetDay md) {
        Model m = new Model();
        m.title = "당일 분석 모델 · " + md.getMeetName();
        try {
            List<String> feats = om.readValue(md.getFeatsJson(), new TypeReference<List<String>>() {});
            Set<String> bin = new HashSet<>(om.readValue(md.getBinJson(), new TypeReference<List<String>>() {}));
            double[] w = om.readValue(md.getDayWeightsJson(), double[].class);
            m.market = w[0];
            m.note = "배당(시장 확률)을 출발점으로 하고 아래 요인으로 보정합니다. 배당에 이미 반영된 것 대비 보정이라, "
                    + "'최근 성적이 나쁠수록 유리'처럼 거꾸로 보이는 항목은 배당이 그 요인을 너무 크게 반영한다는 뜻입니다.";
            fill(m, feats, bin, w, 1);
        } catch (IOException | RuntimeException e) {
            m.note = "가중치를 읽지 못했습니다: " + e.getMessage();
        }
        return m;
    }

    private void fill(Model m, List<String> feats, Set<String> bin, double[] w, int off) {
        double max = 1e-9, total = 0;
        Map<String, Double> g = new LinkedHashMap<>();
        for (int i = 0; i < feats.size() && i + off < w.length; i++) {
            String k = feats.get(i);
            String[] info = F.getOrDefault(k, new String[]{k, "값이 클수록", "값이 작을수록", "기타"});
            Row r = new Row();
            r.key = k; r.name = info[0]; r.group = info[3]; r.w = w[i + off]; r.bin = bin.contains(k); r.up = r.w >= 0;
            if (r.bin) r.effect = info[1] + (r.w >= 0 ? " 유리" : " 불리");
            else r.effect = (r.w >= 0 ? info[1] : info[2]) + " 유리";
            m.rows.add(r);
            max = Math.max(max, Math.abs(r.w));
            total += Math.abs(r.w);
            g.merge(r.group, Math.abs(r.w), Double::sum);
        }
        for (Row r : m.rows) r.bar = Math.abs(r.w) / max * 100;
        m.rows.sort((a, b) -> Double.compare(Math.abs(b.w), Math.abs(a.w)));
        final double tot = total;
        g.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> m.groups.add(new Group(e.getKey(), tot == 0 ? 0 : e.getValue() / tot)));
    }
}
