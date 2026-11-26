package com.sub.gachima.service;

import com.sub.gachima.domain.Entry;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceView;

import java.util.*;

/**
 * 경주 복기: 결과가 나온 뒤 공식 구간 기록(초반 S1F 순위, 4코너 순위, 마지막 200m, 1착과 기록 차)으로
 * "어떻게 달렸고, 가치마가 꼽은 말은 왜 이기거나 졌나"를 몇 문장으로 쓴다.
 * 구간 기록이 없는 경주(직접 넣은 결과, 예전 파일)는 만들지 않는다.
 */
public final class RaceRecap {
    public String headline;
    public final List<String> lines = new ArrayList<>();
    public final List<Row> rows = new ArrayList<>();
    public boolean hasC4;

    public static class Row {
        public int ord, no;
        public String name, tag;
        public Integer s1, c4, g1Rank;
        public Double g1, gap;
        public boolean pick, star, fast;
        public String getS1Text() { return s1 == null ? "-" : String.valueOf(s1); }
        public String getC4Text() { return c4 == null ? "-" : String.valueOf(c4); }
        public String getG1Text() { return g1 == null ? "-" : String.format("%.1f", g1); }
        public String getGapText() { return gap == null ? "-" : gap == 0 ? "–" : String.format("+%.1f", gap); }
    }

    public static RaceRecap of(RaceView v) {
        List<EntryView> fin = new ArrayList<>();
        for (EntryView e : v.entries) if (e.ord != null && e.ord < Entry.ORD_OUT) fin.add(e);
        if (fin.size() < 3) return null;
        int withRun = 0;
        for (EntryView e : fin) if (e.s1 != null || e.g1 != null) withRun++;
        if (withRun * 2 < fin.size()) return null;
        fin.sort(Comparator.comparingInt(e -> e.ord));
        int n = fin.size();
        int front = Math.max(2, (int) Math.round(n * 0.25));

        boolean day = v.isScored();
        EntryView pick = day ? v.dayOrder.get(0) : (v.preOrder.isEmpty() ? null : v.preOrder.get(0));
        String pickLabel = day ? "가치마 1위" : "전날 1위";
        Set<Integer> stars = new HashSet<>();
        if (v.values != null) for (EntryView e : v.values) stars.add(e.no);

        RaceRecap r = new RaceRecap();
        EntryView fastest = null;
        for (EntryView e : fin) {
            Row x = new Row();
            x.ord = e.ord; x.no = e.no; x.name = e.name;
            x.s1 = e.s1; x.c4 = e.c4; x.g1 = e.g1; x.g1Rank = e.g1Rank; x.gap = e.gap;
            x.pick = pick != null && pick.no == e.no;
            x.star = stars.contains(e.no);
            x.fast = e.g1Rank != null && e.g1Rank == 1;
            x.tag = x.pick ? (x.star ? "가치마★" : "가치마") : x.star ? "★" : "";
            if (e.c4 != null) r.hasC4 = true;
            r.rows.add(x);
            if (x.fast) fastest = e;
        }

        // 1) 경주 성격: 우승마가 어디서 왔나
        EntryView w = fin.get(0);
        int frontTop3 = 0;
        for (int i = 0; i < Math.min(3, n); i++) if (fin.get(i).s1 != null && fin.get(i).s1 <= 3) frontTop3++;
        if (w.s1 != null) {
            if (w.s1 <= front) r.headline = "앞에서 간 말이 끝까지 버틴 경주입니다.";
            else if (w.s1 > n / 2.0) r.headline = "뒤에서 따라온 말이 앞을 잡은 경주입니다.";
            else r.headline = "중간에서 자리 잡은 말이 이긴 경주입니다.";
            r.headline += " 1·2·3착 중 초반 3위 안에 있던 말은 " + frontTop3 + "마리.";
        } else {
            r.headline = "결과와 구간 기록으로 다시 본 경주입니다.";
        }

        // 2) 우승마
        r.lines.add("우승 " + w.no + "번 " + w.name + " — " + path(w) + kick(w) + ".");

        // 3) 가치마가 꼽은 말
        if (pick != null && pick.ord != null) {
            String who = pickLabel + " " + pick.no + "번 " + pick.name;
            if (pick.ord == 1) r.lines.add(who + ga(pick.name) + " 그대로 우승했습니다.");
            else r.lines.add(who + " (" + ordText(pick) + "): " + why(pick, n, front));
        }
        // 4) ★ (가치마 1위와 다른 말만)
        if (v.values != null) for (EntryView s : v.values) {
            if (pick != null && s.no == pick.no) continue;
            if (s.ord == null) continue;
            String who = "★ " + s.no + "번 " + s.name;
            if (s.ord == 1) r.lines.add(who + ga(s.name) + " 우승했습니다 (" + path(s) + ").");
            else r.lines.add(who + " (" + ordText(s) + "): " + why(s, n, front));
        }
        // 5) 다음에 볼 말: 마지막 200m가 가장 빨랐는데 3착 밖, 초반 뒤쪽
        if (fastest != null && fastest.ord > 3 && fastest.s1 != null && fastest.s1 > n / 2.0)
            r.lines.add("다음에 볼 말: " + fastest.no + "번 " + fastest.name + " (" + fastest.ord + "착) — 마지막 200m가 가장 빨랐지만 초반 "
                    + fastest.s1 + "위로 뒤에 있어 늦었습니다. 앞에서 풀리는 경주라면 달라질 수 있습니다.");
        return r;
    }

    /** 받침 있으면 '이', 없으면 '가' */
    static String ga(String w) {
        if (w == null || w.isEmpty()) return "이(가)";
        char c = w.charAt(w.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) return "이(가)";
        return (c - 0xAC00) % 28 > 0 ? "이" : "가";
    }

    static String ordText(EntryView e) { return e.ord >= Entry.ORD_OUT ? "착외" : e.ord + "착"; }

    /** 초반 4위 → 4코너 2위 → 1착 */
    static String path(EntryView e) {
        StringBuilder b = new StringBuilder();
        if (e.s1 != null) b.append("초반 ").append(e.s1).append("위 → ");
        if (e.c4 != null) b.append("4코너 ").append(e.c4).append("위 → ");
        b.append(ordText(e));
        return b.toString();
    }

    static String kick(EntryView e) {
        if (e.g1Rank == null) return "";
        return e.g1Rank == 1 ? ", 마지막 200m 가장 빠름" : ", 마지막 200m " + e.g1Rank + "번째로 빠름";
    }

    static String gap(EntryView e) { return e.gap == null ? "" : " (1착과 " + String.format("%.1f", e.gap) + "초)"; }

    /** 진 말이 왜 졌나: 끝에서 밀림 / 뒤에서 따라오다 모자람 / 뒤에서 못 따라옴 / 그 밖 */
    static String why(EntryView e, int n, int front) {
        Integer s1 = e.s1, g = e.g1Rank;
        if (s1 != null && s1 <= front && g != null && g > n / 2.0)
            return "초반 " + s1 + "위로 앞에서 갔지만 마지막 200m가 " + (g == n ? "가장 느려" : n + "마리 중 " + g + "번째라") + " 끝에서 밀렸습니다" + gap(e) + ".";
        if (s1 != null && s1 > n / 2.0 && g != null && g <= 3)
            return "초반 " + s1 + "위로 뒤에 있다가 마지막 200m는 " + (g == 1 ? "가장" : g + "번째로") + " 빨랐지만 따라잡지 못했습니다"
                    + gap(e) + ". 전개가 맞지 않은 경주입니다.";
        if (s1 != null && s1 > n / 2.0)
            return "초반 " + s1 + "위로 뒤에 있었고 끝에서도 따라붙지 못했습니다" + gap(e) + ".";
        return path(e) + kick(e) + gap(e) + ".";
    }
}
