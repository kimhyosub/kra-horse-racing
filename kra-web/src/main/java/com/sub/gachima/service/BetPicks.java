package com.sub.gachima.service;

import com.sub.gachima.service.RaceService.EntryView;

import java.util.*;

/**
 * 승식별 추천 조합: 당일 모델의 우승 확률로 Harville 식을 써서
 * 단승·연승·복승·쌍승·복연승·삼복승·삼쌍승마다 맞을 확률이 가장 높은 조합(과 차선)을 고른다.
 * 단승 말고는 배당을 모르므로 기대값 대신 '가치 배당'(1.1 ÷ 확률, 이 배당 이상이면 기대값 1.1↑)을 보여 준다.
 * 공식 모의 기록은 단승 ★ 만 센다 — 나머지는 참고.
 */
public final class BetPicks {
    public static final double EV_LINE = 1.1;

    public static class Pick {
        public final String type, rule;
        public final List<Integer> nos;
        public final boolean ordered;
        public final double p;
        public List<Integer> alt;
        public double altP;
        /** 단승만: 실제 배당과 기대값 */
        public Double odds, ev;
        public boolean recommended;

        Pick(String type, String rule, List<Integer> nos, boolean ordered, double p) {
            this.type = type; this.rule = rule; this.nos = nos; this.ordered = ordered; this.p = p;
        }
        public String getCombo() { return join(nos, ordered); }
        public String getAltCombo() { return alt == null ? null : join(alt, ordered); }
        public String getPct() { return pct(p); }
        public String getAltPct() { return pct(altP); }
        /** 이 배당 이상이면 기대값 1.1 이상 */
        public String getValueOdds() { return p <= 0 ? "-" : String.format("%.1f", EV_LINE / p); }
    }

    public final List<Pick> picks = new ArrayList<>();
    public String advice, adviceType;
    public boolean isEmpty() { return picks.isEmpty(); }

    public static BetPicks of(List<EntryView> scored) {
        BetPicks b = new BetPicks();
        List<EntryView> hs = new ArrayList<>(scored);
        int n = hs.size();
        if (n < 3) return b;
        hs.sort((x, y) -> Double.compare(y.p, x.p));
        double sum = 0;
        for (EntryView e : hs) sum += e.p;
        double[] p = new double[n];
        int[] no = new int[n];
        for (int i = 0; i < n; i++) { p[i] = hs.get(i).p / sum; no[i] = hs.get(i).no; }

        // Harville: 순서 있는 2착·3착 조합 확률
        double[][] o2 = new double[n][n];
        double[][][] o3 = new double[n][n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (i == j || p[i] >= 1) continue;
            o2[i][j] = p[i] * p[j] / (1 - p[i]);
            for (int k = 0; k < n; k++) {
                if (k == i || k == j || p[i] + p[j] >= 1) continue;
                o3[i][j][k] = o2[i][j] * p[k] / (1 - p[i] - p[j]);
            }
        }
        double[] top2 = new double[n], top3 = new double[n];
        double[][] q2 = new double[n][n], pair3 = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (i == j) continue;
            top2[i] += o2[i][j]; top2[j] += o2[i][j];
            q2[i][j] += o2[i][j]; q2[j][i] += o2[i][j];
            for (int k = 0; k < n; k++) {
                if (k == i || k == j) continue;
                double v = o3[i][j][k];
                top3[i] += v; top3[j] += v; top3[k] += v;
                pair3[i][j] += v; pair3[j][i] += v; pair3[i][k] += v; pair3[k][i] += v; pair3[j][k] += v; pair3[k][j] += v;
            }
        }
        // top2/top3 를 위처럼 더하면 1착 확률이 이미 들어간다 (i 1착 = Σ_j o2[i][j])

        // 단승: ★ 가 있으면 기대값이 가장 큰 ★, 없으면 확률 1위
        EntryView win = null;
        for (EntryView e : hs) if (e.value && (win == null || e.ev > win.ev)) win = e;
        if (win == null) win = hs.get(0);
        Pick w = new Pick("단승", "1착", Collections.singletonList(win.no), false, win.p / sum);
        w.odds = win.odds; w.ev = win.ev;
        int wi = hs.indexOf(win);
        int wAlt = wi == 0 ? 1 : 0;
        w.alt = Collections.singletonList(no[wAlt]); w.altP = p[wAlt];
        b.picks.add(w);

        boolean small = n <= 7;
        b.picks.add(single(small ? top2 : top3, no, "연승", small ? "2착 안 (7두 이하)" : "3착 안"));
        b.picks.add(pair(q2, no, "복승", "1·2착, 순서 상관없음", false));
        b.picks.add(pair(o2, no, "쌍승", "1착→2착 순서대로", true));
        b.picks.add(pair(pair3, no, "복연승", "둘 다 3착 안", false));
        b.picks.add(trio(o3, no, "삼복승", "1~3착 셋, 순서 상관없음", false));
        b.picks.add(trio(o3, no, "삼쌍승", "1→2→3착 순서대로", true));

        // 이 경주에 맞는 방식
        double pa = p[0], pb = p[1];
        boolean star = hs.stream().anyMatch(e -> e.value);
        String a = no[0] + "번", ab = no[0] + "-" + no[1];
        if (star) {
            b.adviceType = "단승";
            b.advice = "★ 가치마 단승이 공식 추천입니다 (모의 기록에 들어가는 것). 다른 승식은 아래 가치 배당보다 높을 때만 참고하세요.";
        } else if (pa < 0.16) {
            b.adviceType = "연승";
            b.advice = "1위 확률이 " + pct(pa) + "뿐인 혼전이라 관망을 권합니다. 굳이 산다면 맞을 확률이 가장 높은 연승 정도.";
        } else if (pa >= 0.35) {
            b.adviceType = "쌍승";
            b.advice = a + "이 " + pct(pa) + "로 축이 뚜렷해, " + a + "을 1착에 둔 쌍승이 어울립니다. 단승은 배당이 낮아 가치가 없습니다.";
        } else if (pa + pb >= 0.45 && pb >= 0.18) {
            b.adviceType = "복승";
            b.advice = ab + "가 합쳐 " + pct(pa + pb) + "로 앞서 둘이 1·2착을 나눠 갖는 복승이 어울립니다.";
        } else {
            b.adviceType = "복연승";
            b.advice = "확실한 축이 없어, 두 마리가 모두 3착 안에만 들면 되는 복연승이 무난합니다.";
        }
        for (Pick x : b.picks) x.recommended = x.type.equals(b.adviceType);
        return b;
    }

    private static Pick single(double[] v, int[] no, String type, String rule) {
        int best = -1, second = -1;
        for (int i = 0; i < v.length; i++) {
            if (best < 0 || v[i] > v[best]) { second = best; best = i; }
            else if (second < 0 || v[i] > v[second]) second = i;
        }
        Pick x = new Pick(type, rule, Collections.singletonList(no[best]), false, Math.min(1, v[best]));
        if (second >= 0) { x.alt = Collections.singletonList(no[second]); x.altP = Math.min(1, v[second]); }
        return x;
    }

    private static Pick pair(double[][] v, int[] no, String type, String rule, boolean ordered) {
        int n = no.length; int[] best = null, second = null; double bp = -1, sp = -1;
        for (int i = 0; i < n; i++) for (int j = ordered ? 0 : i + 1; j < n; j++) {
            if (i == j) continue;
            double q = v[i][j];
            if (q > bp) { second = best; sp = bp; best = new int[]{i, j}; bp = q; }
            else if (q > sp) { second = new int[]{i, j}; sp = q; }
        }
        Pick x = new Pick(type, rule, Arrays.asList(no[best[0]], no[best[1]]), ordered, bp);
        if (second != null) { x.alt = Arrays.asList(no[second[0]], no[second[1]]); x.altP = sp; }
        return x;
    }

    private static Pick trio(double[][][] o3, int[] no, String type, String rule, boolean ordered) {
        int n = no.length; int[] best = null, second = null; double bp = -1, sp = -1;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < n; k++) {
            if (i == j || j == k || i == k) continue;
            if (!ordered && !(i < j && j < k)) continue;
            double q = ordered ? o3[i][j][k]
                    : o3[i][j][k] + o3[i][k][j] + o3[j][i][k] + o3[j][k][i] + o3[k][i][j] + o3[k][j][i];
            if (q > bp) { second = best; sp = bp; best = new int[]{i, j, k}; bp = q; }
            else if (q > sp) { second = new int[]{i, j, k}; sp = q; }
        }
        Pick x = new Pick(type, rule, Arrays.asList(no[best[0]], no[best[1]], no[best[2]]), ordered, bp);
        if (second != null) { x.alt = Arrays.asList(no[second[0]], no[second[1]], no[second[2]]); x.altP = sp; }
        return x;
    }

    static String join(List<Integer> nos, boolean ordered) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < nos.size(); i++) { if (i > 0) s.append(ordered ? "→" : "-"); s.append(nos.get(i)); }
        return s.toString();
    }

    static String pct(double x) { return x < 0.1 ? String.format("%.1f%%", x * 100) : String.format("%.0f%%", x * 100); }
}
