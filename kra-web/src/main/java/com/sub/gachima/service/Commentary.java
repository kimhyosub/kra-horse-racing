package com.sub.gachima.service;

import com.sub.gachima.service.RaceService.EntryView;

import java.util.*;

/**
 * 총평: 모델 숫자만으로 쓰는 '전문가 한마디'.
 *  - 경주 성격: 신마·경험 적은 말 비율, 혼전도(1위 확률)
 *  - 조합 확률: Harville 식 (i 1착, j 2착 = p_i · p_j/(1-p_i) …)
 *  - 다크호스(배당보다 모델이 크게 높게 봄), 과대평가 인기마, 마체중 급변, 장기 휴양
 *  - 결론: 관망 / ★ 중심 / 축 신뢰 / 보통
 * day=false 면 전날 예상 확률로 쓰고 배당 관련 문장은 뺀다.
 */
public final class Commentary {
    public String level, levelClass, headline;
    public final List<String> points = new ArrayList<>();
    public boolean isEmpty() { return headline == null; }

    public static Commentary of(List<EntryView> all, String track, boolean day) {
        Commentary c = new Commentary();
        List<EntryView> hs = new ArrayList<>();
        for (EntryView e : all) if (!day || e.scored) hs.add(e);
        if (hs.size() < 3) return c;
        Map<Integer, Double> p = new HashMap<>();
        for (EntryView e : hs) p.put(e.no, day ? e.p : e.preP);
        hs.sort((a, b) -> Double.compare(p.get(b.no), p.get(a.no)));
        EntryView a = hs.get(0), b = hs.get(1), d = hs.get(2);
        double pa = p.get(a.no), pb = p.get(b.no), pd = p.get(d.no);
        int n = hs.size();

        // 경험
        int debut = 0, few = 0;
        for (EntryView e : hs) {
            if (e.facts == null || e.facts.isEmpty()) continue;   // 근거 데이터가 없는 예전 파일
            Double st = PreReasons.num(e.facts, "starts");
            if (st == null || st == 0) debut++;
            else if (st <= 3) few++;
        }
        boolean rookie = debut >= Math.max(2, n * 0.25) || (debut + few) >= n * 0.5;

        // 조합 확률
        double exact = harville(p, a.no, b.no, d.no);
        double box = 0;
        int[][] perm = {{a.no, b.no, d.no}, {a.no, d.no, b.no}, {b.no, a.no, d.no}, {b.no, d.no, a.no}, {d.no, a.no, b.no}, {d.no, b.no, a.no}};
        for (int[] q : perm) box += harville(p, q[0], q[1], q[2]);
        double inTop3 = pa + pb + pd;
        double aTop3 = top3Prob(p, a.no);

        List<EntryView> values = new ArrayList<>();
        if (day) for (EntryView e : hs) if (e.value) values.add(e);

        // 결론
        boolean open = pa < 0.16 || (rookie && pa < 0.25);
        if (open && values.isEmpty()) { c.level = "관망 권장"; c.levelClass = "wait"; }
        else if (!values.isEmpty()) { c.level = "★ 가치마 중심"; c.levelClass = "star"; }
        else if (pa >= 0.35) { c.level = "축 신뢰"; c.levelClass = "solid"; }
        else { c.level = "보통"; c.levelClass = "mid"; }

        StringBuilder h = new StringBuilder();
        if (rookie) h.append("데뷔전·경험 적은 말이 많아 기록으로 가늠하기 어려운 경주입니다. ");
        if (pa >= 0.35) h.append(a.no).append("번 ").append(a.name).append(josa(a.name, "이", "가")).append(" 우승 확률 ").append(pct(pa)).append("로 뚜렷한 축입니다. ");
        else if (pa < 0.16) h.append("1위 예상 ").append(a.no).append("번도 ").append(pct(pa)).append("에 그치는 혼전입니다. ");
        else h.append(a.no).append("번 ").append(a.name).append(josa(a.name, "이", "가")).append(' ').append(pct(pa)).append("로 앞서지만 압도적이진 않습니다. ");
        if (!values.isEmpty()) {
            EntryView v = values.get(0);
            h.append("배당 대비 가치는 ").append(v.no).append("번 ").append(v.name).append("(★ ").append(odds(v.odds)).append("배, 기대값 ")
             .append(String.format("%.2f", v.ev)).append(")에 있습니다.");
        } else if ("관망 권장".equals(c.level)) {
            h.append("가치마도 없어 이번 경주는 지켜보는 쪽이 낫겠습니다.");
        } else if (day) {
            h.append("배당보다 확실히 높게 볼 말(★)은 없습니다.");
        }
        c.headline = h.toString().trim();

        // 세부
        c.points.add(String.format("예상 1·2·3위(%d-%d-%d)가 순서까지 맞을 확률 %s, 순서 상관없이 셋이 1~3착을 차지할 확률 %s.",
                a.no, b.no, d.no, pct(exact), pct(box)));
        c.points.add(String.format("우승마가 예상 상위 3두 안에서 나올 확률 %s. %d번이 3착 안에 들 확률 %s.", pct(inTop3), a.no, pct(aTop3)));
        List<EntryView> second = new ArrayList<>(hs.subList(1, hs.size()));
        StringBuilder s2 = new StringBuilder(a.no + "번이 1착이면 2착 후보: ");
        for (int i = 0; i < Math.min(2, second.size()); i++) {
            EntryView x = second.get(i);
            if (i > 0) s2.append(", ");
            s2.append(x.no).append("번 ").append(x.name).append(' ').append(pct(p.get(x.no) / (1 - pa)));
        }
        c.points.add(s2.append('.').toString());
        if (debut > 0) c.points.add(String.format("출전 %d두 중 데뷔전 %d두%s — 기록이 없는 말은 모델이 평균값으로 보므로 실제 능력과 차이가 날 수 있습니다.",
                n, debut, few > 0 ? ", 3전 이하 " + few + "두" : ""));

        if (day) {
            // 다크호스: 10배 이상인데 모델/시장 비율이 가장 큰 말
            EntryView dark = null; double best = 1.25;
            for (EntryView e : hs) if (e.odds >= 10 && e.pm > 0 && e.p / e.pm > best) { best = e.p / e.pm; dark = e; }
            if (dark != null) c.points.add(String.format("다크호스: %d번 %s — %s배로 배당은 %s만 보지만 모델은 %s로 봅니다.",
                    dark.no, dark.name, odds(dark.odds), pct(dark.pm), pct(dark.p)));
            // 과대평가 인기마
            EntryView fav = null;
            for (EntryView e : hs) if (fav == null || e.pm > fav.pm) fav = e;
            if (fav != null && fav.p < fav.pm * 0.85)
                c.points.add(String.format("인기마 주의: %d번 %s — 배당 %s배(시장 %s)보다 모델은 %s로 낮게 봅니다.",
                        fav.no, fav.name, odds(fav.odds), pct(fav.pm), pct(fav.p)));
            // 마체중 급변
            List<String> bw = new ArrayList<>();
            for (EntryView e : hs) if (e.inputBwd != null && Math.abs(e.inputBwd) >= 8)
                bw.add(e.no + "번 " + (e.inputBwd > 0 ? "+" : "") + Math.round(e.inputBwd) + "kg");
            if (!bw.isEmpty()) c.points.add("마체중 급변: " + String.join(", ", bw) + " — 컨디션 변수.");
        }
        List<String> rest = new ArrayList<>();
        for (EntryView e : hs) {
            Double dd = PreReasons.num(e.facts, "days");
            if (dd != null && dd >= 90) rest.add(e.no + "번 " + Math.round(dd) + "일");
        }
        if (!rest.isEmpty()) c.points.add("장기 휴양 뒤 출전: " + String.join(", ", rest) + ".");
        if (day && track != null && (track.equals("포화") || track.equals("불량")))
            c.points.add("주로 " + track + ": 젖은 주로는 앞에서 달리는 말이 유리한 편이라 모델이 선행력을 조금 더 쳐줍니다.");
        return c;
    }

    /** Harville: i 1착, j 2착, k 3착 */
    static double harville(Map<Integer, Double> p, int i, int j, int k) {
        double pi = p.get(i), pj = p.get(j), pk = p.get(k);
        if (pi >= 1 || pi + pj >= 1) return 0;
        return pi * pj / (1 - pi) * pk / (1 - pi - pj);
    }

    /** i 가 3착 안에 들 확률 (Harville, 모든 조합 합) */
    static double top3Prob(Map<Integer, Double> p, int i) {
        double s = p.get(i);
        for (int j : p.keySet()) {
            if (j == i) continue;
            double pj = p.get(j);
            s += pj * p.get(i) / (1 - pj);                       // j 1착, i 2착
            for (int k : p.keySet()) {
                if (k == i || k == j) continue;
                double pk = p.get(k);
                s += pj * pk / (1 - pj) * p.get(i) / (1 - pj - pk);   // j 1착, k 2착, i 3착
            }
        }
        return Math.min(1, s);
    }

    /** 받침이 있으면 a, 없으면 b (이/가) */
    static String josa(String w, String a, String b) {
        if (w == null || w.isEmpty()) return b;
        char ch = w.charAt(w.length() - 1);
        if (ch < 0xAC00 || ch > 0xD7A3) return a + "(" + b + ")";
        return (ch - 0xAC00) % 28 != 0 ? a : b;
    }

    static String pct(double x) { return x < 0.1 ? String.format("%.1f%%", x * 100) : String.format("%.0f%%", x * 100); }
    static String odds(double o) { return o == Math.rint(o) ? String.format("%.1f", o) : String.valueOf(o); }
}
