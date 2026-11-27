package com.sub.gachima.telegram;

import com.sub.gachima.domain.Entry;
import com.sub.gachima.domain.PaperBet;
import com.sub.gachima.service.PaperService;
import com.sub.gachima.service.RaceEvent;
import com.sub.gachima.service.RaceService;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceRef;
import com.sub.gachima.service.RaceService.RaceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.annotation.PreDestroy;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 텔레그램 공개 채널 (GACHIMA_TELEGRAM_CHANNEL = @채널이름 또는 -100… ID, 봇이 채널 관리자여야 함).
 *  1. 경주 분석: 출발 전에 당일 입력이 저장되면 1·2·3위와 ★ 를 올리고, 다시 입력하면 같은 글을 고친다
 *  2. 결과: 분석을 올린 경주의 결과가 들어오면 1·2·3착과 ★ 적중 여부
 *  3. 하루 결산: 분석을 올린 경마장의 모든 경주 결과가 들어오면 그날 ★ 손익과 누적 성적 한 번
 * 영천(참고)은 올리지 않는다. 올린 기록은 메모리에만 있어서, 웹을 다시 켜면 그 전에 올린 경주의 결과·결산은 건너뛴다
 * (재시작 뒤 지난 결과가 한꺼번에 올라가는 것을 막음). 오늘 경주만 다룬다.
 */
@Component
public class ChannelPublisher {
    private static final Logger log = LoggerFactory.getLogger(ChannelPublisher.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int REFERENCE_MEET = 4;
    static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};

    private final TelegramClient tg;
    private final RaceService races;
    private final PaperService paper;
    private final com.sub.gachima.service.HomeService home;
    private final String channel, publicUrl;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telegram-channel"); t.setDaemon(true); return t;
    });

    /** "date-meet-rc" → 채널 메시지 번호 */
    final Map<String, Long> posted = new ConcurrentHashMap<>();
    final Set<String> resulted = ConcurrentHashMap.newKeySet();
    /** "date" → 결산 올림 */
    final Set<String> summarized = ConcurrentHashMap.newKeySet();

    public ChannelPublisher(TelegramClient tg, RaceService races, PaperService paper, com.sub.gachima.service.HomeService home,
                            @Value("${gachima.telegram.channel:}") String channel,
                            @Value("${gachima.public-url:}") String publicUrl,
                            Optional<Clock> clock) {
        this.tg = tg; this.races = races; this.paper = paper; this.home = home;
        this.channel = channel == null ? "" : channel.trim();
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", "");
        this.clock = clock.orElse(Clock.system(KST));
    }

    public boolean isEnabled() { return tg.isEnabled() && !channel.isEmpty(); }
    public String getChannel() { return channel; }

    @PreDestroy
    public void stop() { worker.shutdownNow(); }

    /** 저장이 끝난(커밋된) 뒤에 받아서 순서대로 처리 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(RaceEvent e) {
        if (!isEnabled() || e.meet == REFERENCE_MEET || !e.date.equals(today())) return;
        worker.submit(() -> {
            try { handle(e); } catch (Exception x) { log.warn("텔레그램 채널 {} 실패: {}", e, x.getMessage()); }
        });
    }

    void handle(RaceEvent e) throws Exception {
        String key = e.date + "-" + e.meet + "-" + e.rcNo;
        Optional<RaceView> ov = races.view(e.date, e.meet, e.rcNo);
        if (!ov.isPresent()) return;
        RaceView v = ov.get();
        if (e.kind == RaceEvent.Kind.INPUT) {
            if (!v.isScored() || hasResult(v)) return;
            LocalTime at = LocalTime.now(clock.withZone(KST));
            String msg = analysis(v, at);
            Long id = posted.get(key);
            if (id == null) {
                posted.put(key, tg.sendMessage(channel, msg));
                log.info("텔레그램 채널 분석 글 올림: {}", key);
            } else {
                tg.editMessage(channel, id, msg);
                tg.sendReply(channel, updateNote(v, at), id);   // 고친 걸 구독자도 알 수 있게 짧은 답글
                log.info("텔레그램 채널 분석 글 갱신: {}", key);
            }
        } else {
            if (!posted.containsKey(key) || !hasResult(v) || !resulted.add(key)) return;
            tg.sendMessage(channel, result(v));
            maybeSummary(e.date);
        }
    }

    /** 분석을 올린 경마장의 모든 경주에 결과가 있으면 그날 결산을 한 번 */
    void maybeSummary(String date) throws Exception {
        if (summarized.contains(date)) return;
        Set<Integer> meets = new TreeSet<>();
        for (String k : posted.keySet()) if (k.startsWith(date + "-")) meets.add(Integer.parseInt(k.split("-")[1]));
        List<RaceView> all = new ArrayList<>();
        for (RaceRef r : races.racesOn(date)) {
            if (!meets.contains(r.meet)) continue;
            Optional<RaceView> v = races.view(date, r.meet, r.rcNo);
            if (!v.isPresent() || !hasResult(v.get())) return;   // 아직 결과가 안 들어온 경주가 있음
            all.add(v.get());
        }
        if (all.isEmpty() || !summarized.add(date)) return;
        List<RaceView> mine = all.stream().filter(v -> posted.containsKey(date + "-" + v.meet + "-" + v.rcNo)).collect(Collectors.toList());
        tg.sendMessage(channel, summary(date, mine, paper.ledger().getTotal()));
    }

    /** 경주일 아침 09:20 (아침 출전표 수집 08:40 뒤): 오늘 경주 요약과 경주별 가치마 1위 */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${gachima.telegram.morning-cron:0 20 9 * * *}", zone = "Asia/Seoul")
    public void morning() {
        if (!isEnabled()) return;
        try {
            com.sub.gachima.service.HomeService.Home h = home.home();
            if (h.isEmpty() || !h.date.equals(today()) || summarized.contains("morning-" + h.date)) return;
            tg.sendMessage(channel, morningText(h));
            summarized.add("morning-" + h.date);   // 보낸 뒤에만 표시 (실패하면 관리 화면에서 다시 보낼 수 있게)
        } catch (Exception x) {
            log.warn("텔레그램 채널 아침 요약 실패: {}", x.getMessage());
        }
    }

    /** 관리 화면 '아침 글 지금 보내기': 9:20 글을 놓쳤을 때 (이미 보낸 날도 한 번 더 보냄) */
    public String morningNow() {
        if (!isEnabled()) return "공개 채널이 꺼져 있습니다 (GACHIMA_TELEGRAM_CHANNEL)";
        try {
            com.sub.gachima.service.HomeService.Home h = home.home();
            if (h.isEmpty() || !h.date.equals(today())) return "오늘은 경주가 없어 아침 글을 보내지 않았습니다";
            tg.sendMessage(channel, morningText(h));
            summarized.add("morning-" + h.date);
            return "채널 " + channel + "에 오늘 아침 글을 보냈습니다";
        } catch (Exception x) {
            log.warn("텔레그램 채널 아침 요약 실패: {}", x.getMessage());
            return "보내기 실패: " + x.getMessage();
        }
    }

    String morningText(com.sub.gachima.service.HomeService.Home h) {
        StringBuilder b = new StringBuilder();
        b.append("📋 <b>").append(esc(h.label)).append(" 가치마</b>\n").append(esc(h.getMeetNames())).append("\n\n");
        for (String l : h.getSummary()) b.append(esc(l)).append('\n');
        for (com.sub.gachima.service.HomeService.MeetBlock m : h.meets) {
            if (m.reference) continue;
            b.append("\n<b>").append(esc(m.name)).append("</b>\n");
            for (com.sub.gachima.service.HomeService.Row r : m.rows)
                b.append(r.rcNo).append("R ").append(r.stTime == null ? "" : esc(r.stTime) + " ").append(r.first.no).append("번 ")
                 .append(esc(r.first.name)).append(' ').append(r.getP1Text()).append(r.isClear() ? " ●" : "").append('\n');
        }
        b.append("\n<i>● 1위 확률 30% 이상 · ★ 가치마는 경주 직전 배당으로 경주마다 따로 올립니다.</i>");
        link(b, "/", "오늘 경주 전체 보기");
        return b.toString();
    }

    static boolean hasResult(RaceView v) {
        return v.entries.stream().anyMatch(x -> x.ord != null && x.ord < Entry.ORD_OUT);
    }

    // ---------------- 글 ----------------

    /** 다시 입력했을 때 원글에 다는 한 줄: 🔄 12:01 배당으로 갱신 · 1위 7번 OO 32% · ★ 없음 */
    String updateNote(RaceView v, LocalTime at) {
        StringBuilder b = new StringBuilder("🔄 ").append(at.format(DateTimeFormatter.ofPattern("HH:mm"))).append(" 배당으로 갱신");
        List<EntryView> top = v.getDayTop3();
        if (!top.isEmpty()) b.append(" · 1위 ").append(top.get(0).no).append("번 ").append(esc(top.get(0).name)).append(' ').append(pct(top.get(0).p));
        if (v.values.isEmpty()) b.append(" · ★ 없음");
        else {
            b.append(" · ★");
            for (EntryView x : v.values) b.append(' ').append(x.no).append("번(").append(odds(x.odds)).append("배)");
        }
        return b.toString();
    }

    String analysis(RaceView v, LocalTime at) {
        StringBuilder b = new StringBuilder();
        b.append("🏇 <b>").append(esc(v.meetName)).append(' ').append(v.rcNo).append("R</b>");
        if (v.stTime != null && !v.stTime.isEmpty()) b.append(" · ").append(esc(v.stTime)).append(" 출발");
        b.append(" · ").append(v.dist).append("m");
        if (v.track != null) b.append(" · ").append(esc(v.track));
        b.append("\n\n");
        List<EntryView> top = v.getDayTop3();
        for (int i = 0; i < top.size(); i++) {
            EntryView x = top.get(i);
            b.append(i + 1).append("위 ").append(x.no).append("번 ").append(esc(x.name)).append("  ")
             .append(pct(x.p)).append(" · ").append(odds(x.odds)).append("배").append(x.value ? " ★" : "").append('\n');
        }
        b.append('\n');
        if (v.values.isEmpty()) b.append("★ 가치마 없음 — 배당에 비해 확률이 높은 말이 없습니다\n");
        else for (EntryView x : v.values)
            b.append("★ <b>").append(x.no).append("번 ").append(esc(x.name)).append("</b>  단승 ").append(odds(x.odds))
             .append("배 · 기대값 ").append(String.format("%.2f", x.ev)).append('\n');
        b.append("<i>").append(at.format(DateTimeFormatter.ofPattern("HH:mm"))).append(" 배당 기준</i>");
        link(b, "/r/" + v.date + "/" + v.meet + "/" + v.rcNo + "?mode=day", "경주 분석 보기");
        return b.toString();
    }

    String result(RaceView v) {
        StringBuilder b = new StringBuilder();
        b.append("🏁 <b>").append(esc(v.meetName)).append(' ').append(v.rcNo).append("R 결과</b>\n");
        List<EntryView> placed = v.entries.stream().filter(x -> x.ord != null && x.ord <= 3)
                .sorted(Comparator.comparingInt(x -> x.ord)).collect(Collectors.toList());
        for (EntryView x : placed) {
            b.append(x.ord).append("착 ").append(x.no).append("번 ").append(esc(x.name));
            if (x.finalOdds != null) b.append(" (").append(odds(x.finalOdds)).append("배)");
            if (x.dayRank != null) b.append(" · 당일 ").append(x.dayRank).append("위");
            b.append('\n');
        }
        List<PaperBet> bets = v.paperBets;
        if (bets.isEmpty()) b.append("\n★ 없던 경주");
        else {
            b.append('\n');
            for (PaperBet x : bets) {
                b.append("★ ").append(x.getChulNo()).append("번 ").append(esc(x.getHrName())).append(" → ");
                if (x.isWin()) b.append("<b>적중</b> ").append(odds(x.getPayout())).append("배 (+").append(won((x.getPayout() - 1) * 1000)).append(")");
                else if (x.isScratched()) b.append("출전 취소");
                else b.append(esc(x.getStatus())).append(" (-1,000원)");
                b.append('\n');
            }
            b.setLength(b.length() - 1);
        }
        return b.toString();
    }

    String summary(String date, List<RaceView> mine, PaperService.Row total) {
        LocalDate d = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        StringBuilder b = new StringBuilder();
        b.append("📊 <b>").append(d.getMonthValue()).append('/').append(d.getDayOfMonth()).append('(').append(DOW[d.getDayOfWeek().getValue() - 1])
         .append(") 가치마 결산</b>\n\n");
        int n = mine.size(), top1 = 0, bets = 0, wins = 0;
        double ret = 0;
        for (RaceView v : mine) {
            if (v.isScored() && v.dayOrder.get(0).ord != null && v.dayOrder.get(0).ord == 1) top1++;
            for (PaperBet x : v.paperBets) {
                if (!x.isCounted()) continue;
                bets++;
                if (x.isWin()) { wins++; ret += x.getPayout(); }
            }
        }
        b.append("분석 ").append(n).append("경주 · 당일 1위 우승 ").append(top1).append("번\n");
        if (bets == 0) b.append("★ 가치마 없음\n");
        else b.append("★ ").append(bets).append("건 중 ").append(wins).append("건 적중 · 1,000원씩 ")
              .append(signedWon((ret - bets) * 1000)).append('\n');
        if (total != null && total.n > 0)
            b.append("누적 ★ ").append(total.n).append("건 · 회수율 ").append(total.getRoi()).append('\n');
        b.append("\n<i>틀린 것까지 모두 공개합니다. 분석은 참고용이며 구매는 본인 판단으로.</i>");
        link(b, "/stats?date=" + date, "검증 리포트");
        return b.toString();
    }

    /** 일간 총평을 올렸다고 채널에 알림 */
    public void announceDaily(String date, String title, String summary, String path) {
        if (!isEnabled()) return;
        try {
            LocalDate d = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
            StringBuilder b = new StringBuilder();
            b.append("📝 <b>").append(d.getMonthValue()).append('/').append(d.getDayOfMonth()).append('(').append(DOW[d.getDayOfWeek().getValue() - 1])
             .append(") 일간 총평</b>\n\n").append(esc(summary));
            link(b, path, "총평 읽기");
            tg.sendMessage(channel, b.toString());
        } catch (Exception x) {
            log.warn("텔레그램 채널 일간 총평 알림 실패: {}", x.getMessage());
        }
    }

    private void link(StringBuilder b, String path, String label) {
        if (!publicUrl.isEmpty()) b.append("\n<a href=\"").append(esc(publicUrl + path)).append("\">").append(label).append(" ›</a>");
    }

    private String today() { return LocalDate.now(clock.withZone(KST)).format(DateTimeFormatter.BASIC_ISO_DATE); }

    static String pct(double p) { return String.format("%.0f%%", p * 100); }
    static String odds(double o) { return String.format("%.1f", o); }
    static String won(double w) { return String.format("%,d원", Math.round(w)); }
    static String signedWon(double w) { return (w >= 0 ? "+" : "-") + won(Math.abs(w)); }
    static String esc(String s) { return RaceSummary.esc(s); }
}
