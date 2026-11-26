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
        int n, hit, bets, betWins;
        double exp, betRet;
        final List<String> rows = new ArrayList<>();
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
            vs.add(ov.get());
        }
        if (vs.isEmpty()) return Optional.empty();

        Map<String, Meet> meets = new LinkedHashMap<>();
        Meet all = new Meet();
        int favWins = 0, frontWins = 0, frontKnown = 0;
        RaceView bigRace = null; EntryView big = null;
        Map<String, List<String>> jk = new LinkedHashMap<>(), tr = new LinkedHashMap<>();
        List<String> upsets = new ArrayList<>(), watch = new ArrayList<>();
        boolean anyOfficial = false;
        for (RaceView v : vs) {
            Meet m = meets.computeIfAbsent(v.meetName, k -> { Meet x = new Meet(); x.name = k; return x; });
            EntryView w = winner(v);
            String where = v.meetName + " " + v.rcNo + "R";
            // 결과 표 한 줄
            m.rows.add("| " + v.rcNo + " | " + (w == null ? "-" : w.no + "번 " + w.name) + " | " + (w == null || w.jk == null ? "-" : w.jk)
                    + " | " + (w == null || w.finalOdds == null ? "-" : odds(w.finalOdds)) + " | " + div(v, "복승") + " | " + div(v, "삼쌍승") + " |");
            if (w != null) {
                int pop = popRank(v, w);
                if (pop == 1) favWins++;
                if (w.s1 != null) { frontKnown++; if (w.s1 <= 3) frontWins++; }
                if (w.jk != null && !w.jk.isEmpty()) jk.computeIfAbsent(w.jk, k -> new ArrayList<>()).add(where);
                if (w.tr != null && !w.tr.isEmpty()) tr.computeIfAbsent(w.tr, k -> new ArrayList<>()).add(where);
                if (w.finalOdds != null) {
                    if (big == null || w.finalOdds > big.finalOdds) { big = w; bigRace = v; }
                    if (w.finalOdds >= 15) {
                        String run = w.s1 == null ? "" : ", " + RaceRecap.path(w);
                        upsets.add("- **" + where + " " + w.no + "번 " + w.name + "** 단승 " + odds(w.finalOdds) + "배 — 인기 " + (pop > 0 ? pop + "위" : "-") + run + " " + raceLink(v));
                    }
                }
            }
            // 다음에 볼 말: 막판이 가장 빨랐는데 자리가 뒤라 3착 밖
            for (EntryView e : v.entries)
                if (e.g1Rank != null && e.g1Rank == 1 && e.ord != null && e.ord > 3 && e.ord <= v.entries.size() && e.s1 != null && e.s1 > v.entries.size() / 2.0)
                    watch.add("- **" + where + " " + e.no + "번 " + e.name + "** — " + e.ord + "착, 막판 200m 가장 빠름 (초반 " + e.s1 + "위)");
            // 가치마 (짧게)
            EntryView p = pick(v);
            if (p != null) {
                double p1 = v.isScored() ? p.p : p.preP;
                for (Meet x : new Meet[]{m, all}) { x.n++; x.exp += p1; if (p.ord != null && p.ord == 1) x.hit++; }
            }
            for (PaperBet b : v.paperBets) {
                if (!b.isCounted()) continue;
                anyOfficial = true;
                for (Meet x : new Meet[]{m, all}) { x.bets++; if (b.isWin()) { x.betWins++; x.betRet += b.getPayout(); } }
            }
        }
        int nRaces = vs.size();
        List<Map.Entry<String, List<String>>> topJk = top(jk), topTr = top(tr);

        LocalDate d = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        String label = d.getMonthValue() + "월 " + d.getDayOfMonth() + "일(" + DOW[d.getDayOfWeek().getValue() - 1] + ")";
        String meetNames = String.join("·", meets.keySet());

        Review r = new Review();
        r.date = date;
        r.slug = "daily-" + date;
        r.title = label + " 경마 결과 총평 · " + meetNames + " " + nRaces + "경주";
        StringBuilder sm = new StringBuilder();
        sm.append("인기 1위 말 ").append(favWins).append("승");
        if (big != null) sm.append(", 최고 배당 ").append(bigRace.meetName).append(' ').append(bigRace.rcNo).append("R 단승 ").append(odds(big.finalOdds)).append("배");
        if (!topJk.isEmpty()) sm.append(", 최다승 기수 ").append(topJk.get(0).getKey()).append(' ').append(topJk.get(0).getValue().size()).append("승");
        sm.append(". 경주별 1착과 배당, 기수, 다음에 볼 말을 정리했습니다.");
        r.summary = sm.toString();

        StringBuilder b = new StringBuilder();
        b.append(label).append(' ').append(meetNames).append("에서 열린 ").append(nRaces).append("경주를 정리했습니다.\n\n");

        b.append("# 오늘 경마 한눈에\n\n");
        double favRate = 1.0 * favWins / nRaces;
        b.append("- **인기 1위 말 ").append(favWins).append("승 / ").append(nRaces).append("경주** — ")
         .append(favRate >= 0.45 ? "인기마가 강했던 날" : favRate <= 0.22 ? "이변이 많았던 날" : "보통 세 경주에 한 번꼴이라 평범한 날").append('\n');
        if (big != null) b.append("- **가장 큰 배당**: ").append(bigRace.meetName).append(' ').append(bigRace.rcNo).append("R ").append(big.no).append("번 ").append(big.name)
                          .append(" 단승 ").append(odds(big.finalOdds)).append("배\n");
        if (!topJk.isEmpty()) b.append("- **최다승 기수**: ").append(topJk.get(0).getKey()).append(' ').append(topJk.get(0).getValue().size()).append("승\n");
        if (frontKnown > 0) b.append("- **앞에서 간 말이 이긴 경주**: ").append(frontWins).append(" / ").append(frontKnown).append("경주")
                             .append(frontWins * 2 > frontKnown ? " (선행이 유리했던 날)" : " (뒤에서 따라온 말도 많이 이긴 날)").append('\n');
        b.append('\n');

        for (Meet m : meets.values()) {
            b.append("# ").append(m.name).append(" 결과\n\n| R | 1착 | 기수 | 단승 | 복승 | 삼쌍승 |\n|---|---|---|---|---|---|\n");
            for (String row : m.rows) b.append(row).append('\n');
            b.append('\n');
        }
        b.append("배당은 건 돈의 몇 배를 돌려받는지 보여 주는 확정 배당입니다.\n\n");

        b.append("# 기수 · 조교사\n\n");
        b.append(topJk.isEmpty() || topJk.get(0).getValue().size() < 2 ? "- 2승 이상 거둔 기수는 없었습니다.\n" : "- 기수: " + names(topJk) + "\n");
        b.append(topTr.isEmpty() || topTr.get(0).getValue().size() < 2 ? "- 2승 이상 거둔 조교사는 없었습니다.\n" : "- 조교사: " + names(topTr) + "\n");
        b.append('\n');

        b.append("# 이변 경주\n\n");
        if (upsets.isEmpty()) b.append("단승 15배 이상 나온 경주는 없었습니다.\n\n");
        else { for (String l : upsets) b.append(l).append('\n'); b.append('\n'); }

        if (!watch.isEmpty()) {
            b.append("# 다음에 볼 말\n\n마지막 200m는 가장 빨랐는데 초반 자리가 뒤라 늦은 말입니다. 다음 출전 때 눈여겨볼 만합니다.\n\n");
            for (String l : watch.subList(0, Math.min(5, watch.size()))) b.append(l).append('\n');
            b.append('\n');
        }

        b.append("# 가치마는\n\n");
        b.append("가치마 1위 말 ").append(all.hit).append("승 (모델 예상 ").append(String.format("%.1f", all.exp)).append("승)");
        if (anyOfficial) b.append(" · ★ ").append(all.bets).append("건 중 ").append(all.betWins).append("건 적중, ").append(signedWon((all.betRet - all.bets) * 1000));
        else b.append(" · 출발 전 입력이 없어 공식 ★ 기록 없음");
        b.append(". 자세한 숫자는 [검증 리포트](/stats?date=").append(date).append(")에서 볼 수 있습니다.\n\n");
        b.append("> 경주가 끝난 뒤 한국마사회 공식 기록으로 자동으로 쓴 글입니다. 마권 구매는 본인 판단으로, 감당할 수 있는 범위에서만 하세요.\n");
        r.body = b.toString();
        return Optional.of(r);
    }

    /** 승수 많은 순 (같으면 먼저 나온 순) */
    static List<Map.Entry<String, List<String>>> top(Map<String, List<String>> m) {
        List<Map.Entry<String, List<String>>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        return l;
    }
    /** 2승 이상만: 문세영 3승 (서울 2R·5R·9R), … */
    static String names(List<Map.Entry<String, List<String>>> l) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, List<String>> e : l) {
            if (e.getValue().size() < 2) break;
            if (b.length() > 0) b.append(", ");
            b.append("**").append(e.getKey()).append(' ').append(e.getValue().size()).append("승** (").append(String.join("·", e.getValue())).append(')');
        }
        return b.toString();
    }
    /** 확정 배당이 낮은 순 인기 순위 (배당 없으면 0) */
    static int popRank(RaceView v, EntryView e) {
        if (e.finalOdds == null) return 0;
        int r = 1;
        for (EntryView x : v.entries) if (x.finalOdds != null && x.finalOdds < e.finalOdds) r++;
        return r;
    }
    static String div(RaceView v, String k) { Double x = v.divs.get(k); return x == null ? "-" : odds(x); }

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
