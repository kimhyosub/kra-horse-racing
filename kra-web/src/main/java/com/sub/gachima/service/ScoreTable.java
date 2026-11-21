package com.sub.gachima.service;

import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceView;

import java.util.*;

/**
 * 경주 점수표: 말 × 요인. 점수 0~100 (50 = 이 경주 평균), 칸마다 근거 숫자.
 * 점수는 전날 모델에서 그 요인 묶음이 이 말의 우승 확률을 올리고 내린 정도(로짓 기여)를 50 ± 50·tanh(c/0.25) 로 바꾼 것.
 */
public final class ScoreTable {

    /** 열: 이름, 묶는 전날 모델 요인 */
    public static final LinkedHashMap<String, List<String>> COLS = new LinkedHashMap<>();
    static {
        COLS.put("최근 성적", Arrays.asList("form3", "place_rate"));
        COLS.put("전개", Arrays.asList("early4", "behind3", "best_behind"));
        COLS.put("막판 속도", Collections.singletonList("g1f4"));
        COLS.put("거리", Arrays.asList("dist_place", "dist_change"));
        COLS.put("간격", Arrays.asList("log_days", "first"));
        COLS.put("기수·조교사", Arrays.asList("jk_rate", "tr_rate", "jk_change"));
        COLS.put("등급·중량·나이", Arrays.asList("cls_change", "tool_change_f", "budam_change", "wgBudam", "age", "rating"));
        COLS.put("심판", Arrays.asList("ref_last_f", "ref_180"));
    }

    public static class Cell {
        public final int score; public final String raw;
        Cell(int score, String raw) { this.score = score; this.raw = raw; }
        /** 색 단계 1(약)~5(강) */
        public int getLevel() { return score >= 70 ? 5 : score >= 58 ? 4 : score > 42 ? 3 : score > 30 ? 2 : 1; }
    }

    public static class Row {
        public EntryView h;
        public List<Cell> cells = new ArrayList<>();
        public int total;            // 전날 확률을 점수로 (경주 1위 = 100)
    }

    public final List<String> cols = new ArrayList<>(COLS.keySet());
    public final List<Row> rows = new ArrayList<>();
    public boolean scored;

    public static ScoreTable of(RaceView v) {
        ScoreTable t = new ScoreTable();
        t.scored = v.isScored();
        double maxP = v.entries.stream().mapToDouble(e -> e.preP).max().orElse(1);
        for (EntryView h : v.preOrder) {
            Row r = new Row();
            r.h = h;
            r.total = (int) Math.round(h.preP / maxP * 100);
            for (Map.Entry<String, List<String>> c : COLS.entrySet()) {
                double sum = 0;
                for (String f : c.getValue()) sum += h.preWhy.getOrDefault(f, 0.0);
                r.cells.add(new Cell((int) Math.round(50 + 50 * Math.tanh(sum / 0.25)), raw(c.getKey(), h.facts)));
            }
            t.rows.add(r);
        }
        return t;
    }

    static String raw(String col, Map<String, Object> f) {
        if (f == null || f.isEmpty()) return "";
        switch (col) {
            case "최근 성적": {
                String r = PreReasons.recent(f, 3);
                return r.isEmpty() ? "기록 없음" : r.replace("최근 ", "");
            }
            case "전개": {
                Double e = PreReasons.num(f, "early");
                return e == null ? "-" : e <= 0.25 ? "선두권" : e <= 0.5 ? "앞쪽" : e <= 0.75 ? "중간~뒤" : "후미";
            }
            case "막판 속도": {
                Double g = PreReasons.num(f, "g1f");
                return g == null ? "-" : (g <= 0 ? "" : "+") + String.format("%.1f초", g);
            }
            case "거리": {
                Double ds = PreReasons.num(f, "distStarts"), dt = PreReasons.num(f, "distTop3");
                return ds == null || ds == 0 ? "처음" : "입상 " + PreReasons.fmt(PreReasons.orZero(dt)) + "/" + PreReasons.fmt(ds);
            }
            case "간격": {
                Double st = PreReasons.num(f, "starts"), d = PreReasons.num(f, "days");
                return st == null || st == 0 ? "데뷔" : d == null ? "-" : PreReasons.fmt(d) + "일";
            }
            case "기수·조교사": {
                Double j = PreReasons.num(f, "jkWin"), tr = PreReasons.num(f, "trWin");
                return (j == null ? "-" : String.format("%.0f%%", j)) + " · " + (tr == null ? "-" : String.format("%.0f%%", tr));
            }
            case "등급·중량·나이": {
                List<String> p = new ArrayList<>();
                Double c = PreReasons.num(f, "clsChange"), bc = PreReasons.num(f, "budamChange"), age = PreReasons.num(f, "age");
                if (c != null && c != 0) p.add(c < 0 ? "승급" : "강급");
                if (bc != null && bc != 0) p.add((bc > 0 ? "+" : "") + PreReasons.fmt(bc) + "kg");
                if (age != null) p.add(PreReasons.fmt(age) + "세");
                return String.join(" ", p);
            }
            case "심판": {
                Double n = PreReasons.num(f, "ref180");
                return n == null || n == 0 ? "-" : PreReasons.fmt(n) + "회";
            }
            default: return "";
        }
    }
}
