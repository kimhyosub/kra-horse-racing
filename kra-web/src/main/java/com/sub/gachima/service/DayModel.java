package com.sub.gachima.service;

import java.util.*;

/**
 * 당일 모델: 시장 배당을 출발점으로 요인을 더해 다시 계산한 우승 확률.
 * kra-raceday/kra_features.py 의 score()/race_matrix() 와 같은 계산이다.
 *
 *   u_i = w0 * log(시장확률_i) + Σ_j  z_ij * w_j
 *   z_ij = 경주 안에서 표준화한 요인값 (이진 요인은 평균만 뺀다, 빈 값은 경주 평균)
 *   p_i = softmax(u)
 */
public final class DayModel {

    public static final double RULE_EV = 1.1;
    public static final double RULE_MAX_ODDS = 20.0;

    /** 화면에 보이는 요인 묶음 */
    public static final LinkedHashMap<String, List<String>> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("전개(선행력)", Arrays.asList("early4", "behind3", "best_behind", "wet_early"));
        GROUPS.put("속도 기록", Collections.singletonList("g1f4"));
        GROUPS.put("최근 폼", Arrays.asList("form3", "place_rate"));
        GROUPS.put("거리 적합", Arrays.asList("dist_place", "dist_change"));
        GROUPS.put("컨디션·마체중", Arrays.asList("log_days", "first", "bwd", "risky_f", "stable_f"));
        GROUPS.put("기수·조교사", Arrays.asList("jk_rate", "tr_rate", "jk_change"));
        GROUPS.put("조건 변화", Arrays.asList("cls_change", "tool_change_f", "budam_change", "wgBudam", "age", "rating"));
        GROUPS.put("심판 기록", Arrays.asList("ref_last_f", "ref_180"));
    }

    /** 계산에 들어가는 출전마 한 두 */
    public static final class Horse {
        public final int no;
        public final Double[] f;      // 요인값 (당일 입력 요인은 비어 있음)
        public final Double days;
        public final double odds;
        public final Double bwd;      // 마체중 증감, 모르면 null

        public Horse(int no, Double[] f, Double days, double odds, Double bwd) {
            this.no = no; this.f = f; this.days = days; this.odds = odds; this.bwd = bwd;
        }
    }

    public static final class Result {
        public final int no;
        public final double odds, pMarket, pModel, ev;
        public final boolean value;
        public final Map<String, Double> groups;

        Result(int no, double odds, double pMarket, double pModel, Map<String, Double> groups) {
            this.no = no; this.odds = odds; this.pMarket = pMarket; this.pModel = pModel;
            this.ev = pModel * odds;
            this.value = ev >= RULE_EV && odds <= RULE_MAX_ODDS;
            this.groups = groups;
        }
    }

    private final List<String> feats;
    private final Set<String> bin;
    private final double[] w;
    private final Map<String, Integer> ix = new HashMap<>();

    public DayModel(List<String> feats, Collection<String> bin, double[] weights) {
        if (weights.length != feats.size() + 1)
            throw new IllegalArgumentException("가중치 수(" + weights.length + ")가 요인 수+1(" + (feats.size() + 1) + ")과 다릅니다");
        this.feats = feats;
        this.bin = new HashSet<>(bin);
        this.w = weights;
        for (int i = 0; i < feats.size(); i++) ix.put(feats.get(i), i);
    }

    /** 배당이 있는 말이 2두 미만이면 빈 목록 */
    public List<Result> score(List<Horse> horses, String track) {
        List<Horse> a = new ArrayList<>();
        for (Horse h : horses) if (h.odds > 0) a.add(h);
        if (a.size() < 2) return Collections.emptyList();
        boolean wet = "포화".equals(track) || "불량".equals(track);
        int n = a.size(), k = feats.size();

        Double[][] raw = new Double[n][];
        for (int i = 0; i < n; i++) {
            Horse h = a.get(i);
            Double[] f = h.f.clone();
            set(f, "bwd", h.bwd);
            set(f, "risky_f", h.bwd != null && h.bwd >= 5 ? 1.0 : 0.0);
            set(f, "stable_f", h.bwd != null && h.bwd >= -4 && h.bwd <= 4 && h.days != null && h.days <= 35 ? 1.0 : 0.0);
            Double e4 = get(f, "early4");
            set(f, "wet_early", wet ? -(e4 == null ? 0.5 : e4) : 0.0);
            raw[i] = f;
        }

        double[][] z = new double[n][k];
        for (int j = 0; j < k; j++) {
            double sum = 0; int cnt = 0;
            for (int i = 0; i < n; i++) { Double v = raw[i][j]; if (v != null && !v.isNaN()) { sum += v; cnt++; } }
            double fill = cnt > 0 ? sum / cnt : 0.0;
            double[] v = new double[n];
            double mean = 0;
            for (int i = 0; i < n; i++) { Double x = raw[i][j]; v[i] = (x == null || x.isNaN()) ? fill : x; mean += v[i]; }
            mean /= n;
            double var = 0;
            for (int i = 0; i < n; i++) var += (v[i] - mean) * (v[i] - mean);
            double sd = Math.sqrt(var / n);
            boolean centerOnly = bin.contains(feats.get(j)) || sd < 1e-9;
            for (int i = 0; i < n; i++) z[i][j] = centerOnly ? v[i] - mean : (v[i] - mean) / sd;
        }

        double invSum = 0;
        for (Horse h : a) invSum += 1.0 / h.odds;
        double[] pm = new double[n], u = new double[n];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            pm[i] = (1.0 / a.get(i).odds) / invSum;
            double s = w[0] * Math.log(pm[i]);
            for (int j = 0; j < k; j++) s += z[i][j] * w[j + 1];
            u[i] = s;
            max = Math.max(max, s);
        }
        double se = 0;
        double[] ex = new double[n];
        for (int i = 0; i < n; i++) { ex[i] = Math.exp(u[i] - max); se += ex[i]; }

        List<Result> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Double> g = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : GROUPS.entrySet()) {
                double s = 0;
                for (String f : e.getValue()) { Integer j = ix.get(f); if (j != null) s += z[i][j] * w[j + 1]; }
                g.put(e.getKey(), s);
            }
            out.add(new Result(a.get(i).no, a.get(i).odds, pm[i], ex[i] / se, g));
        }
        return out;
    }

    private void set(Double[] f, String name, Double v) { Integer j = ix.get(name); if (j != null) f[j] = v; }
    private Double get(Double[] f, String name) { Integer j = ix.get(name); return j == null ? null : f[j]; }
}
