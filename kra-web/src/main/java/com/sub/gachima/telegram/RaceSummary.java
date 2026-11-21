package com.sub.gachima.telegram;

import com.sub.gachima.domain.Entry;
import com.sub.gachima.domain.PaperBet;
import com.sub.gachima.service.PaperService;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceView;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** 텔레그램으로 보낼 당일 분석 요약 (HTML) */
public final class RaceSummary {
    private RaceSummary() { }

    public static String of(RaceView v, String publicUrl, String by, List<String> warnings) {
        StringBuilder b = new StringBuilder();
        b.append("🏇 <b>").append(esc(v.meetName)).append(' ').append(v.rcNo).append("R</b>");
        if (v.stTime != null && !v.stTime.isEmpty()) b.append(' ').append(esc(v.stTime)).append(" 출발");
        b.append(" · ").append(v.dist).append("m");
        if (v.track != null) b.append(" · 주로 ").append(esc(v.track));
        b.append('\n');

        if (v.review != null && !v.review.isEmpty()) {
            b.append("\n💬 <b>총평 · ").append(esc(v.review.level)).append("</b>\n").append(esc(v.review.headline)).append('\n');
            for (int i = 0; i < Math.min(3, v.review.points.size()); i++) b.append("· ").append(esc(v.review.points.get(i))).append('\n');
        }
        if (!v.isScored()) {
            b.append("당일 분석: 배당이 2두 이상 있어야 계산됩니다.\n");
        } else {
            b.append("\n<b>당일 분석</b>\n");
            String[] medal = {"1위", "2위", "3위"};
            List<EntryView> top = v.getDayTop3();
            for (int i = 0; i < top.size(); i++) {
                EntryView e = top.get(i);
                b.append(medal[i]).append("  ").append(e.no).append("번 ").append(esc(e.name))
                 .append("  ").append(pct(e.p)).append(" (").append(odds(e.odds)).append("배)");
                if (e.value) b.append(" ★");
                b.append('\n');
            }
            b.append("\n<b>★ 가치마</b>\n");
            if (v.values.isEmpty()) b.append("없음\n");
            for (EntryView e : v.values)
                b.append(e.no).append("번 ").append(esc(e.name)).append("  ").append(odds(e.odds)).append("배 · 모델 ")
                 .append(pct(e.p)).append(" · 기대값 ").append(String.format("%.2f", e.ev)).append('\n');
        }

        if (v.picks != null && !v.picks.isEmpty()) {
            b.append("\n<b>승식별 추천</b> · 이 경주는 ").append(esc(v.picks.adviceType)).append('\n');
            for (com.sub.gachima.service.BetPicks.Pick k : v.picks.picks) {
                b.append(k.recommended ? "👉 " : "· ").append(k.type).append(' ').append(k.getCombo()).append("  ").append(k.getPct());
                if (k.odds == null) b.append(" (").append(k.getValueOdds()).append("배↑면 가치)");
                b.append('\n');
            }
        }
        b.append('\n');
        if (v.paperLock == null || v.paperLock == PaperService.Outcome.RECORDED) b.append("모의 기록: 이번 입력으로 저장");
        else if (v.paperLock == PaperService.Outcome.LOCKED_RESULT) b.append("모의 기록: 결과가 나온 경주라 그대로");
        else b.append("모의 기록: 출발 후라 그대로");
        if (by != null) b.append(" · 입력 ").append(esc(by));
        b.append('\n');
        if (warnings != null) for (String w : warnings) b.append("⚠ ").append(esc(w)).append('\n');
        if (publicUrl != null && !publicUrl.isEmpty())
            b.append("<a href=\"").append(esc(publicUrl)).append("/r/").append(v.date).append('/').append(v.meet).append('/').append(v.rcNo)
             .append("?mode=day\">경주 화면 열기</a>");
        return b.toString();
    }

    /** 결과를 넣은 뒤의 답장: 1·2·3착, 전날·당일 예상이 어땠는지, ★ 모의 기록 정산 */
    public static String result(RaceView v, String publicUrl, String by, List<String> warnings) {
        StringBuilder b = new StringBuilder();
        b.append("🏁 <b>").append(esc(v.meetName)).append(' ').append(v.rcNo).append("R 결과</b>");
        if (by != null) b.append(" · 입력 ").append(esc(by));
        b.append('\n');
        List<EntryView> placed = v.entries.stream().filter(e -> e.ord != null && e.ord < Entry.ORD_OUT)
                .sorted(Comparator.comparingInt(e -> e.ord)).collect(Collectors.toList());
        for (EntryView e : placed) {
            b.append(e.ord).append("착  ").append(e.no).append("번 ").append(esc(e.name));
            if (e.finalOdds != null) b.append(" · 단승 ").append(odds(e.finalOdds)).append("배");
            b.append(" · 전날 ").append(e.preRank).append("위");
            if (e.dayRank != null) b.append(" · 당일 ").append(e.dayRank).append("위");
            if (e.value) b.append(" ★");
            b.append('\n');
        }
        List<String> out = v.entries.stream().filter(e -> e.ord == null).map(e -> e.no + "번").collect(Collectors.toList());
        if (!out.isEmpty()) b.append("취소: ").append(String.join(", ", out)).append('\n');

        b.append("\n<b>예상은</b>\n");
        if (!v.preOrder.isEmpty()) {
            EntryView p = v.preOrder.get(0);
            b.append("전날 1위 ").append(p.no).append("번 ").append(esc(p.name)).append(" → ").append(p.ord == null ? "취소" : p.getOrdLabel()).append('\n');
        }
        if (v.isScored()) {
            EntryView d = v.dayOrder.get(0);
            b.append("당일 1위 ").append(d.no).append("번 ").append(esc(d.name)).append(" → ").append(d.ord == null ? "취소" : d.getOrdLabel()).append('\n');
        }

        b.append("\n<b>★ 모의 기록</b>\n");
        if (v.paperBets.isEmpty()) b.append("없음\n");
        for (PaperBet x : v.paperBets) {
            b.append(x.getChulNo()).append("번 ").append(esc(x.getHrName())).append(' ').append(odds(x.getBetOdds())).append("배 → ");
            if (x.isWin()) b.append("적중 +").append(String.format("%.1f", x.getPayout() - 1));
            else if (x.isScratched()) b.append("취소 (건수 제외)");
            else b.append(esc(x.getStatus())).append(" -1");
            b.append('\n');
        }
        b.append("\n공식 결과가 수집되면 자동으로 덮어씁니다.\n");
        if (warnings != null) for (String w : warnings) b.append("⚠ ").append(esc(w)).append('\n');
        if (publicUrl != null && !publicUrl.isEmpty())
            b.append("<a href=\"").append(esc(publicUrl)).append("/r/").append(v.date).append('/').append(v.meet).append('/').append(v.rcNo)
             .append("?mode=day\">경주 화면 열기</a>");
        return b.toString();
    }

    static String pct(double p) { return String.format("%.1f%%", p * 100); }
    static String odds(double o) { return o == Math.rint(o) ? String.format("%.1f", o) : String.valueOf(o); }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
