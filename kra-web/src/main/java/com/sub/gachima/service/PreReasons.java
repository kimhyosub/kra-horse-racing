package com.sub.gachima.service;

import java.util.*;

/**
 * 전날 예상의 근거를 사람이 읽는 말로.
 *  - record(): 늘 보여 주는 기록 한 줄 (7전 2승 · 최근 1·4·1착 · 28일 만 · 기수 승률 14.9%)
 *  - of(): 순위를 가장 크게 움직인 요인 3개 (▲ 올림 / ▼ 내림) + 그 근거 숫자
 * why 값은 같은 경주 출전마 평균 대비 로짓 기여도라, 0.1 이면 우승 확률을 대략 10% 정도(상대적으로) 올린 셈.
 */
public final class PreReasons {
    private PreReasons() { }

    public static final double MIN = 0.06;   // 이보다 작으면 근거로 안 보여 줌

    public static class Reason {
        public final boolean up;
        public final String title, detail;
        public final double size;
        Reason(boolean up, String title, String detail, double size) { this.up = up; this.title = title; this.detail = detail; this.size = size; }
        public String getArrow() { return up ? "▲" : "▼"; }
    }

    /** 요인 기여가 큰 순서로 최대 n개. 같은 뜻(behind3·best_behind, form3·place_rate)은 하나로 */
    public static List<Reason> of(Map<String, Double> why, Map<String, Object> f, int n) {
        if (why == null || why.isEmpty()) return Collections.emptyList();
        Map<String, Double> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : why.entrySet()) {
            String k = e.getKey();
            if (k.equals("best_behind")) k = "behind3";
            if (k.equals("place_rate")) k = "form3";
            merged.merge(k, e.getValue(), Double::sum);
        }
        List<Map.Entry<String, Double>> list = new ArrayList<>(merged.entrySet());
        list.sort((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));
        List<Reason> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : list) {
            if (out.size() >= n || Math.abs(e.getValue()) < MIN) break;
            Reason r = reason(e.getKey(), e.getValue(), f == null ? Collections.<String, Object>emptyMap() : f);
            if (r != null) out.add(r);
        }
        out.sort((a, b) -> Double.compare(b.size, a.size));   // 올린 것 먼저
        return out;
    }

    static Reason reason(String k, double c, Map<String, Object> f) {
        boolean up = c > 0;
        switch (k) {
            case "form3": return new Reason(up, up ? "최근 성적 좋음" : "최근 성적 부진", recent(f, 3) + top3(f), c);
            case "early4": return new Reason(up, up ? "앞에서 달리는 편" : "뒤에서 달리는 편", early(f), c);
            case "behind3": return new Reason(up, up ? "1착과 차이가 작았음" : "1착과 차이가 컸음", "최근 3전 기준", c);
            case "g1f4": return new Reason(up, up ? "막판 속도 빠름" : "막판 속도 느림", g1f(f), c);
            case "dist_place": return new Reason(up, up ? "이 거리 성적 좋음" : "이 거리 성적 약함", dist(f), c);
            case "dist_change": return new Reason(up, up ? "거리 변화 유리" : "거리 변화 불리", distChange(f), c);
            case "log_days": return new Reason(up, up ? "간격 알맞음" : "간격 김", days(f), c);
            case "first": return new Reason(up, up ? "데뷔전" : "데뷔전", "기록 없이 첫 출전", c);
            case "jk_rate": return new Reason(up, up ? "기수 승률 높음" : "기수 승률 낮음", rate(f, "jkWin", "jkStarts"), c);
            case "tr_rate": return new Reason(up, up ? "조교사 승률 높음" : "조교사 승률 낮음", rate(f, "trWin", "trStarts"), c);
            case "jk_change": return new Reason(up, "기수 교체", "지난 경주와 다른 기수", c);
            case "cls_change": return new Reason(up, up ? "등급 변화 유리" : "등급 변화 불리", cls(f), c);
            case "tool_change_f": return new Reason(up, "장구 바뀜", "지난 경주와 장구가 다름", c);
            case "budam_change": return new Reason(up, up ? "부담중량 변화 유리" : "부담중량 변화 불리", budamChange(f), c);
            case "wgBudam": return new Reason(up, up ? "부담중량 유리" : "부담중량 불리", num(f, "budam") == null ? "" : fmt(num(f, "budam")) + "kg", c);
            case "age": return new Reason(up, up ? "나이 유리" : "나이 불리", num(f, "age") == null ? "" : fmt(num(f, "age")) + "세 (어릴수록 유리한 편)", c);
            case "rating": return new Reason(up, up ? "레이팅 높음" : "레이팅 낮음", num(f, "rating") == null ? "" : "레이팅 " + fmt(num(f, "rating")), c);
            case "ref_last_f": return new Reason(up, "지난 경주 심판 기록", "지난번엔 진로 방해 등 불리한 일이 있었음", c);
            case "ref_180": return new Reason(up, "최근 심판 기록", num(f, "ref180") == null ? "" : "최근 6개월 " + fmt(num(f, "ref180")) + "회", c);
            default: return null;
        }
    }

    /** 늘 보여 주는 기록 한 줄 */
    public static String record(Map<String, Object> f) {
        if (f == null || f.isEmpty()) return "";
        List<String> p = new ArrayList<>();
        Double st = num(f, "starts");
        if (st == null || st == 0) p.add("데뷔전");
        else {
            p.add(fmt(st) + "전 " + fmt(orZero(num(f, "wins"))) + "승 · 입상 " + fmt(orZero(num(f, "top3"))));
            String r = recent(f, 5);
            if (!r.isEmpty()) p.add(r);
            String d = days(f);
            if (!d.isEmpty()) p.add(d);
        }
        String jk = rate(f, "jkWin", "jkStarts");
        if (!jk.isEmpty()) p.add("기수 " + jk.replace(" (1년 ", " (").replace("전)", "전)"));
        return String.join(" · ", p);
    }

    // ---- 근거 숫자 ----

    @SuppressWarnings("unchecked")
    static String recent(Map<String, Object> f, int n) {
        Object o = f.get("recent");
        if (!(o instanceof List) || ((List<Object>) o).isEmpty()) return "";
        List<Object> l = (List<Object>) o;
        StringBuilder b = new StringBuilder("최근 ");
        for (int i = 0; i < Math.min(n, l.size()); i++) { if (i > 0) b.append('·'); b.append(l.get(i)); }
        return b.append("착").toString();
    }

    static String top3(Map<String, Object> f) {
        Double st = num(f, "starts"), t3 = num(f, "top3");
        return st == null || st == 0 || t3 == null ? "" : " · 입상 " + fmt(t3) + "/" + fmt(st);
    }

    static String early(Map<String, Object> f) {
        Double e = num(f, "early");
        if (e == null) return "초반 위치 기록 없음";
        return "최근 초반 위치: " + (e <= 0.25 ? "선두권" : e <= 0.5 ? "앞쪽" : e <= 0.75 ? "중간~뒤" : "후미");
    }

    static String g1f(Map<String, Object> f) {
        Double g = num(f, "g1f");
        if (g == null) return "기록 없음";
        if (Math.abs(g) < 0.05) return "마지막 200m가 그 경주 평균 수준";
        return String.format("마지막 200m가 그 경주 중간보다 %.1f초 %s", Math.abs(g), g < 0 ? "빠름" : "느림");
    }

    static String dist(Map<String, Object> f) {
        Double ds = num(f, "distStarts"), dt = num(f, "distTop3"), d = num(f, "dist");
        String at = d == null ? "이 거리" : fmt(d) + "m 안팎";
        if (ds == null || ds == 0) return at + " 처음";
        return at + " 입상 " + fmt(orZero(dt)) + "/" + fmt(ds);
    }

    static String distChange(Map<String, Object> f) {
        Double a = num(f, "lastDist"), b = num(f, "dist");
        if (a == null || b == null) return "";
        if (a.equals(b)) return "지난번과 같은 " + fmt(b) + "m";
        return fmt(a) + "m → " + fmt(b) + "m";
    }

    static String days(Map<String, Object> f) {
        Double d = num(f, "days");
        return d == null ? "" : fmt(d) + "일 만";
    }

    static String rate(Map<String, Object> f, String win, String starts) {
        Double w = num(f, win), s = num(f, starts);
        if (w == null || s == null || s == 0) return "";
        return String.format("%.1f%%", w) + " (1년 " + fmt(s) + "전)";
    }

    static String cls(Map<String, Object> f) {
        Double c = num(f, "clsChange");
        if (c == null || c == 0) return "지난번과 같은 등급";
        return c < 0 ? "한 단계 위 등급으로 올라옴" : "한 단계 아래 등급으로 내려옴";
    }

    static String budamChange(Map<String, Object> f) {
        Double c = num(f, "budamChange");
        if (c == null || c == 0) return "지난번과 같음";
        return String.format("지난번보다 %s%skg", c > 0 ? "+" : "", fmt(c));
    }

    static Double num(Map<String, Object> f, String k) {
        Object o = f.get(k);
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }

    static double orZero(Double d) { return d == null ? 0 : d; }

    static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
}
