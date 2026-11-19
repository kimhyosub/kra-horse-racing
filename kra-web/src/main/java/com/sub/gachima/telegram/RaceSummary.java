package com.sub.gachima.telegram;

import com.sub.gachima.service.PaperService;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceView;

import java.util.List;

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

    static String pct(double p) { return String.format("%.1f%%", p * 100); }
    static String odds(double o) { return o == Math.rint(o) ? String.format("%.1f", o) : String.valueOf(o); }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
