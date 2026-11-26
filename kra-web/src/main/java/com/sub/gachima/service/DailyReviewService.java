package com.sub.gachima.service;

import com.sub.gachima.domain.Entry;
import com.sub.gachima.domain.MeetDay;
import com.sub.gachima.domain.PaperBet;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceRef;
import com.sub.gachima.service.RaceService.RaceView;
import com.sub.gachima.telegram.ChannelPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 일간 총평: 경주일 밤(공식 구간 기록이 들어온 뒤) 그날 경주를 자동으로 돌아보는 글.
 *  - 가치마 1위 우승 수 vs 모델이 예상한 우승 수, 3착 안
 *  - ★ 가치마(출발 전 공식 기록) 한 건씩 + 어떻게 달렸나
 *  - 가치마 1위가 가장 자신 있었는데 진 경주, 큰 배당, 다음에 볼 말
 * 글 주소는 daily-YYYYMMDD, 분류 daily. 운영자가 관리 화면에서 고친 글은 다시 쓰지 않는다.
 * 영천은 참고 경마장이라 뺀다.
 */
@Service
public class DailyReviewService {
    private static final Logger log = LoggerFactory.getLogger(DailyReviewService.class);
    static final int REFERENCE_MEET = 4;
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};

    private final RaceService races;
    private final PostService posts;
    private final ObjectProvider<ChannelPublisher> channel;
    private final Clock clock;

    public DailyReviewService(RaceService races, PostService posts, ObjectProvider<ChannelPublisher> channel, Optional<Clock> clock) {
        this.races = races; this.posts = posts; this.channel = channel;
        this.clock = clock.orElse(Clock.system(KST));
    }

    /** 매일 밤 23:10: 최근 7일 경주일 총평을 만들거나 새로 고친다 (오늘 것은 채널에도 알림) */
    @Scheduled(cron = "${gachima.daily-review.cron:0 10 23 * * *}", zone = "Asia/Seoul")
    public void nightly() {
        String today = today();
        String from = LocalDate.now(clock.withZone(KST)).minusDays(7).format(DateTimeFormatter.BASIC_ISO_DATE);
        Set<String> dates = new TreeSet<>();
        for (MeetDay md : races.allMeetDays())
            if (md.getRaceDate().compareTo(from) >= 0 && md.getRaceDate().compareTo(today) <= 0) dates.add(md.getRaceDate());
        for (String d : dates) {
            try {
                Optional<Review> r = build(d);
                if (!r.isPresent()) continue;
                Boolean created = save(r.get());
                if (Boolean.TRUE.equals(created) && d.equals(today)) {
                    ChannelPublisher c = channel.getIfAvailable();
                    if (c != null) c.announceDaily(d, r.get().title, r.get().summary, "/guide/" + r.get().slug);
                }
            } catch (Exception e) {
                log.warn("일간 총평 {} 실패: {}", d, e.toString());
            }
        }
    }

    public Boolean save(Review r) {
        LocalDate d = LocalDate.parse(r.date, DateTimeFormatter.BASIC_ISO_DATE);
        Boolean b = posts.upsertAuto(r.slug, r.title, r.summary, r.body, "daily", d.atTime(22, 0));
        if (b != null) log.info("일간 총평 {} {}", r.date, b ? "새로 씀" : "고침");
        return b;
    }

    public static class Review {
        public String date, slug, title, summary, body;
    }

    static class Meet {
        String name;
        int n, hit, top3, bets, betWins, post, postWins;
        double exp, betRet, postRet;
    }

    /** 결과가 다 나온 날만 (오늘이면 모든 경주, 지난 날이면 결과가 있는 경주로) */
    public Optional<Review> build(String date) {
        boolean isToday = date.equals(today());
        List<RaceView> vs = new ArrayList<>();
        for (RaceRef ref : races.racesOn(date)) {
            if (ref.meet == REFERENCE_MEET) continue;
            Optional<RaceView> ov = races.view(date, ref.meet, ref.rcNo);
            if (!ov.isPresent()) continue;
            if (!hasResult(ov.get())) { if (isToday) return Optional.empty(); continue; }
            if (pick(ov.get()) == null) continue;
            vs.add(ov.get());
        }
        if (vs.isEmpty()) return Optional.empty();

        Map<String, Meet> meets = new LinkedHashMap<>();
        Meet all = new Meet();
        List<String> starLines = new ArrayList<>(), missLines = new ArrayList<>(), bigLines = new ArrayList<>(), watchLines = new ArrayList<>();
        List<RaceView> misses = new ArrayList<>();
        boolean anyOfficial = false;
        for (RaceView v : vs) {
            Meet m = meets.computeIfAbsent(v.meetName, k -> { Meet x = new Meet(); x.name = k; return x; });
            EntryView p = pick(v);
            double p1 = v.isScored() ? p.p : p.preP;
            boolean hit = p.ord != null && p.ord == 1, top3 = p.ord != null && p.ord <= 3;
            for (Meet x : new Meet[]{m, all}) { x.n++; x.exp += p1; if (hit) x.hit++; if (top3) x.top3++; }
            if (!hit) misses.add(v);

            // ★ 공식 (출발 전 모의 기록)
            for (PaperBet b : v.paperBets) {
                if (!b.isCounted()) continue;
                anyOfficial = true;
                for (Meet x : new Meet[]{m, all}) { x.bets++; if (b.isWin()) { x.betWins++; x.betRet += b.getPayout(); } }
                EntryView e = byNo(v, b.getChulNo());
                String why = e == null ? "" : RaceRecap.reason(v, e);
                starLines.add("- **" + v.meetName + " " + v.rcNo + "R " + b.getChulNo() + "번 " + b.getHrName() + "** — 단승 " + odds(b.getBetOdds()) + "배 기록, "
                        + (b.isWin() ? "**적중 " + odds(b.getPayout()) + "배**" : b.getFinishOrd() >= Entry.ORD_OUT ? "착외" : b.getFinishOrd() + "착")
                        + (why.isEmpty() ? "" : ". " + why) + " " + raceLink(v));
            }
            // ★ 사후 (출발 전 입력이 없던 경주: 확정 배당으로 다시 계산)
            if (v.paperBets.isEmpty() && v.isScored() && v.values != null)
                for (EntryView s : v.values) {
                    for (Meet x : new Meet[]{m, all}) { x.post++; if (s.ord != null && s.ord == 1) { x.postWins++; x.postRet += s.finalOdds == null ? s.odds : s.finalOdds; } }
                }

            // 큰 배당
            EntryView w = winner(v);
            if (w != null && w.finalOdds != null && w.finalOdds >= 15) {
                String why = RaceRecap.reason(v, w);
                bigLines.add("- **" + v.meetName + " " + v.rcNo + "R " + w.no + "번 " + w.name + "** 단승 " + odds(w.finalOdds) + "배 — 가치마 순위 "
                        + rankOf(v, w) + (why.isEmpty() ? "" : ", " + why) + " " + raceLink(v));
            }
            // 다음에 볼 말
            if (v.recap != null)
                for (String l : v.recap.lines)
                    if (l.startsWith("다음에 볼 말: ")) watchLines.add("- **" + v.meetName + " " + v.rcNo + "R** " + l.substring("다음에 볼 말: ".length()));
        }
        // 가장 자신 있었는데 진 경주 3개
        misses.sort((a, b) -> Double.compare(p1(b), p1(a)));
        for (RaceView v : misses.subList(0, Math.min(3, misses.size()))) {
            EntryView p = pick(v);
            String why = RaceRecap.reason(v, p);
            EntryView w = winner(v);
            missLines.add("- **" + v.meetName + " " + v.rcNo + "R** 가치마 1위 " + p.no + "번 " + p.name + " (" + pct(p1(v)) + ") "
                    + (p.ord == null || p.ord >= Entry.ORD_OUT || p.ord > v.entries.size() ? "착순 없음" : p.ord + "착")
                    + (w == null ? "" : ", 우승은 " + w.no + "번 " + w.name + (w.finalOdds == null ? "" : " (" + odds(w.finalOdds) + "배)"))
                    + (why.isEmpty() ? "." : ". " + why) + " " + raceLink(v));
        }

        LocalDate d = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        String label = d.getMonthValue() + "월 " + d.getDayOfMonth() + "일(" + DOW[d.getDayOfWeek().getValue() - 1] + ")";
        String meetNames = String.join("·", meets.keySet());

        Review r = new Review();
        r.date = date;
        r.slug = "daily-" + date;
        String head = headline(all);
        String starShort = anyOfficial ? "★ " + all.bets + "건 중 " + all.betWins + "건" : "공식 ★ 없음";
        r.title = "일간 총평 · " + label + " " + meetNames + " — 가치마 1위 " + all.hit + "/" + all.n + "승, " + starShort;
        r.summary = label + " " + meetNames + " " + all.n + "경주. " + head + " "
                + (anyOfficial ? "★ 가치마는 " + all.bets + "건 중 " + all.betWins + "건 적중, " + signedWon((all.betRet - all.bets) * 1000) + "."
                               : "출발 전 당일 배당 입력이 없어 공식 ★ 기록은 없습니다.");

        StringBuilder b = new StringBuilder();
        b.append(label).append(' ').append(meetNames).append(' ').append(all.n).append("경주를 돌아봅니다. 영천은 참고 경마장이라 뺐습니다. 경주가 끝난 뒤 공식 기록으로 자동으로 쓴 글입니다.\n\n");
        b.append("# 한 줄 요약\n\n**").append(head).append("**\n\n");

        // 표
        List<Meet> ms = new ArrayList<>(meets.values());
        boolean multi = ms.size() > 1;
        b.append("| |");
        for (Meet m : ms) b.append(' ').append(m.name).append(" |");
        if (multi) b.append(" 합계 |");
        b.append("\n|---|");
        for (int i = 0; i < ms.size() + (multi ? 1 : 0); i++) b.append("---|");
        b.append('\n');
        if (multi) ms.add(all);
        row(b, "경주", ms, m -> String.valueOf(m.n));
        row(b, "가치마 1위 우승", ms, m -> (m == all ? "**" + m.hit + "**" : String.valueOf(m.hit)));
        row(b, "모델이 예상한 우승 수", ms, m -> String.format("%.1f", m.exp));
        row(b, "가치마 1위 3착 안", ms, m -> m.top3 + " (" + pct(m.n == 0 ? 0 : 1.0 * m.top3 / m.n) + ")");
        if (anyOfficial) {
            row(b, "★ 건수 · 적중", ms, m -> m.bets + " · " + m.betWins);
            row(b, "★ 손익 (1,000원씩)", ms, m -> m.bets == 0 ? "-" : (m == all ? "**" + signedWon((m.betRet - m.bets) * 1000) + "**" : signedWon((m.betRet - m.bets) * 1000)));
        }
        b.append('\n');
        b.append("모델이 매긴 가치마 1위 확률을 모두 더한 값이 '예상한 우승 수'입니다. 실제 우승 수가 이 숫자 근처면 확률대로 맞은 날입니다.\n\n");

        b.append("# ★ 가치마\n\n");
        if (anyOfficial) {
            for (String l : starLines) b.append(l).append('\n');
            b.append("\n★ 는 출발 전 마지막 배당으로 기록하고 확정 배당으로 정산한 공식 모의 장부 기준입니다.\n\n");
        } else {
            b.append("이날은 출발 전 당일 배당 입력이 없어 공식 ★ 기록이 없습니다.");
            if (all.post > 0)
                b.append(" 경주 후 확정 배당으로 다시 계산하면 ★ ").append(all.post).append("건 중 ").append(all.postWins).append("건이 맞았고, 1,000원씩이면 ")
                 .append(signedWon((all.postRet - all.post) * 1000)).append("입니다(참고용).");
            b.append("\n\n");
        }

        if (!missLines.isEmpty()) {
            b.append("# 가치마 1위가 진 경주\n\n확률이 가장 높았는데 이기지 못한 경주입니다.\n\n");
            for (String l : missLines) b.append(l).append('\n');
            b.append('\n');
        }
        if (!bigLines.isEmpty()) {
            b.append("# 큰 배당이 나온 경주\n\n");
            for (String l : bigLines) b.append(l).append('\n');
            b.append('\n');
        }
        if (!watchLines.isEmpty()) {
            b.append("# 다음에 볼 말\n\n마지막 200m가 그 경주에서 가장 빨랐지만 초반 자리가 뒤라 3착 안에 못 든 말입니다.\n\n");
            for (String l : watchLines.subList(0, Math.min(6, watchLines.size()))) b.append(l).append('\n');
            b.append('\n');
        }
        b.append("이날 숫자 전체는 [검증 리포트](/stats?date=").append(date).append(")에서, 하루 결과를 어떻게 봐야 하는지는 [모의 장부 읽는 법](/guide/paper-ledger)에서 볼 수 있습니다.\n\n");
        b.append("> 하루 결과는 대부분 운입니다. 가치마의 분석은 참고용이며, 마권 구매는 본인의 판단과 책임으로 감당할 수 있는 범위에서만 하세요.\n");
        r.body = b.toString();
        return Optional.of(r);
    }

    interface Cell { String of(Meet m); }
    private static void row(StringBuilder b, String label, List<Meet> ms, Cell c) {
        b.append("| ").append(label).append(" |");
        for (Meet m : ms) b.append(' ').append(c.of(m)).append(" |");
        b.append('\n');
    }

    static String headline(Meet a) {
        String s;
        if (a.hit >= a.exp + 1.5) s = "가치마 1위가 평소보다 잘 맞은 날입니다";
        else if (a.hit <= a.exp - 1.5) s = "이변이 많아 가치마 1위가 평소보다 덜 맞은 날입니다";
        else s = "가치마 1위는 확률대로 맞은 날입니다";
        return s + String.format(" (%d경주 %d승, 예상 %.1f승).", a.n, a.hit, a.exp);
    }

    static EntryView pick(RaceView v) {
        if (v.isScored()) return v.dayOrder.get(0);
        return v.preOrder == null || v.preOrder.isEmpty() ? null : v.preOrder.get(0);
    }
    static double p1(RaceView v) { EntryView p = pick(v); return v.isScored() ? p.p : p.preP; }
    static boolean hasResult(RaceView v) { return v.entries.stream().anyMatch(x -> x.ord != null && x.ord < Entry.ORD_OUT); }
    static EntryView winner(RaceView v) { for (EntryView e : v.entries) if (e.ord != null && e.ord == 1) return e; return null; }
    static EntryView byNo(RaceView v, int no) { for (EntryView e : v.entries) if (e.no == no) return e; return null; }
    static String rankOf(RaceView v, EntryView e) {
        List<EntryView> order = v.isScored() ? v.dayOrder : v.preOrder;
        for (int i = 0; i < order.size(); i++) if (order.get(i).no == e.no) return (i + 1) + "위";
        return "-";
    }
    static String raceLink(RaceView v) { return "[경주 보기](/r/" + v.date + "/" + v.meet + "/" + v.rcNo + (v.isScored() ? "?mode=day" : "") + ")"; }
    static String pct(double x) { return String.format("%.0f%%", x * 100); }
    static String odds(double o) { return String.format("%.1f", o); }
    static String signedWon(double w) { long r = Math.round(w); return (r >= 0 ? "+" : "-") + String.format("%,d원", Math.abs(r)); }
    private String today() { return LocalDate.now(clock.withZone(KST)).format(DateTimeFormatter.BASIC_ISO_DATE); }
}
