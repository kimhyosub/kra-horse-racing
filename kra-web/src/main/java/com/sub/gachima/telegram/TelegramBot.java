package com.sub.gachima.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.sub.gachima.service.PaperService;
import com.sub.gachima.service.RaceService;
import com.sub.gachima.service.RaceService.RaceRef;
import com.sub.gachima.service.RaceService.RaceView;
import com.sub.gachima.service.ScreenshotReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텔레그램 봇.
 *  - 허용된 대화(GACHIMA_TELEGRAM_CHATS)에서 스크린샷을 보내면: 읽기 → 어느 경주인지 찾기 → 당일 입력으로 바로 저장
 *    → 당일 분석 1·2·3위와 ★ 가치마를 답장, 다른 허용 대화에도 알림
 *  - 웹에서 당일 입력을 저장해도 허용 대화 전부에 같은 요약을 보낸다 (notifyAll)
 *  - 사진 설명(캡션)에 "서울 7", "부경 5R", "다습" 처럼 쓰면 경주·주로를 그대로 쓴다
 * 서버로 들어오는 주소가 필요 없는 long polling 방식이라 집 PC 에서 그대로 돈다.
 */
@Component
public class TelegramBot {
    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Map<String, Integer> MEETS = new LinkedHashMap<>();
    static {
        MEETS.put("서울", 1); MEETS.put("제주", 2); MEETS.put("부경", 3); MEETS.put("부산", 3); MEETS.put("영천", 4);
    }

    private final TelegramClient tg;
    private final ScreenshotReader reader;
    private final RaceService races;
    private final Set<Long> chats;
    private final String publicUrl;
    private final Clock clock;
    private volatile boolean running;
    private long offset;

    public TelegramBot(TelegramClient tg, ScreenshotReader reader, RaceService races,
                       @Value("${gachima.telegram.chats:}") String chats,
                       @Value("${gachima.public-url:}") String publicUrl,
                       Optional<Clock> clock) {
        this.tg = tg; this.reader = reader; this.races = races;
        this.chats = parseChats(chats);
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
        this.clock = clock.orElse(Clock.system(KST));
    }

    static Set<Long> parseChats(String s) {
        Set<Long> out = new LinkedHashSet<>();
        if (s != null) for (String x : s.split("[,\\s]+")) if (!x.trim().isEmpty()) out.add(Long.parseLong(x.trim()));
        return out;
    }

    public boolean isEnabled() { return tg.isEnabled(); }
    public Set<Long> getChats() { return Collections.unmodifiableSet(chats); }

    // ---------------- long polling ----------------

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!tg.isEnabled()) { log.info("텔레그램 봇 꺼짐 (TELEGRAM_BOT_TOKEN 없음)"); return; }
        running = true;
        Thread t = new Thread(this::loop, "telegram-bot");
        t.setDaemon(true);
        t.start();
        log.info("텔레그램 봇 시작, 허용 대화 {}개", chats.size());
    }

    @PreDestroy
    public void stop() { running = false; }

    private void loop() {
        while (running) {
            try {
                for (JsonNode u : tg.getUpdates(offset, 50)) {
                    offset = u.path("update_id").asLong() + 1;
                    try { handle(u); } catch (Exception e) { log.warn("텔레그램 메시지 처리 실패", e); }
                }
            } catch (Exception e) {
                log.warn("텔레그램 연결 오류: {} (5초 뒤 다시)", e.getMessage());
                sleep(5000);
            }
        }
    }

    // ---------------- 메시지 처리 ----------------

    void handle(JsonNode update) throws Exception {
        JsonNode m = update.path("message");
        if (m.isMissingNode()) return;
        long chat = m.path("chat").path("id").asLong();
        long msgId = m.path("message_id").asLong();
        String text = m.path("text").asText(""), caption = m.path("caption").asText("");
        String who = name(m.path("from"));

        if (text.startsWith("/start") || text.startsWith("/id")) {
            tg.sendMessage(chat, "가치마 봇입니다. 이 대화 ID: <code>" + chat + "</code>\n"
                    + (chats.contains(chat) ? "허용된 대화입니다. 경주 배당 스크린샷을 보내 주세요. 캡션에 \"서울 7\"처럼 경주를 적어도 됩니다."
                                            : "아직 허용되지 않았습니다. 주인이 PC 환경변수 GACHIMA_TELEGRAM_CHATS 에 이 ID를 넣어야 합니다."), msgId);
            return;
        }
        String fileId = null;
        if (m.path("photo").isArray() && m.path("photo").size() > 0) fileId = m.path("photo").get(m.path("photo").size() - 1).path("file_id").asText();
        else if (m.path("document").path("mime_type").asText("").startsWith("image/")) fileId = m.path("document").path("file_id").asText();
        if (fileId == null) return;   // 사진이 아니면 조용히 넘어감 (단톡방 잡담)
        if (!chats.contains(chat)) { tg.sendMessage(chat, "허용되지 않은 대화입니다. /id 로 ID를 확인해 주인에게 알려 주세요.", msgId); return; }
        if (!reader.isEnabled()) { tg.sendMessage(chat, "스크린샷 읽기가 꺼져 있습니다 (PC에 ANTHROPIC_API_KEY 필요).", msgId); return; }

        tg.typing(chat);
        LocalDate todayDate = LocalDate.now(clock.withZone(KST));
        Hint hint = parseCaption(caption, todayDate);
        String today = (hint.date != null ? hint.date : todayDate).format(DateTimeFormatter.BASIC_ISO_DATE);
        List<RaceRef> cands = races.racesOn(today);
        if (cands.isEmpty()) {
            tg.sendMessage(chat, (hint.date != null ? "" : "오늘") + "(" + today.substring(4, 6) + "/" + today.substring(6) + ") 경주 데이터가 없습니다. "
                    + (hint.date != null ? "날짜를 확인해 주세요." : "자동 수집이 돌았는지 관리 화면을 확인하거나, 다른 날이면 캡션에 \"11/15 서울 7\"처럼 날짜를 적어 주세요."), msgId);
            return;
        }

        byte[] img = tg.download(fileId);
        String mime = m.path("document").path("mime_type").asText("image/jpeg");
        ScreenshotReader.Reading r = reader.read(Collections.singletonList(new ScreenshotReader.Image(img, mime.startsWith("image/") ? mime : "image/jpeg")),
                null, 0, Collections.<Integer, String>emptyMap());

        LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
        Choice c = choose(cands, r, hint, now);
        if (c == null) { tg.sendMessage(chat, "어느 경주인지 찾지 못했습니다. 캡션에 \"서울 7\"처럼 경주를 적어 다시 보내 주세요.", msgId); return; }
        if (r.odds.size() < 2) { tg.sendMessage(chat, "배당을 2두 이상 읽지 못했습니다 (" + c.race.meetName + " " + c.race.rcNo + "R). 단승 배당이 보이는 화면으로 다시 보내 주세요.", msgId); return; }

        List<String> warn = new ArrayList<>();
        warn.add(c.how);
        String track = hint.track != null ? hint.track : r.track;
        if (track == null) {
            track = races.lastManualTrack(today, c.race.meet).orElse("건조");
            warn.add("주로 상태를 못 읽어 '" + track + "'(으)로 계산했습니다. 다르면 캡션에 \"다습\"처럼 적어 다시 보내 주세요.");
        }
        List<Integer> missing = new ArrayList<>();
        for (Integer no : c.race.names.keySet()) if (!r.odds.containsKey(no)) missing.add(no);
        if (!missing.isEmpty()) warn.add("배당을 못 읽은 마번 " + missing + " 은 출전 취소로 계산했습니다.");

        Map<Integer, String> odds = new HashMap<>(), bwd = new HashMap<>();
        for (Integer no : c.race.names.keySet()) {
            odds.put(no, r.odds.containsKey(no) ? String.valueOf(r.odds.get(no)) : "");
            bwd.put(no, r.bwd.containsKey(no) ? String.valueOf(r.bwd.get(no)) : "");
        }
        String by = "tg:" + who;
        races.saveInput(today, c.race.meet, c.race.rcNo, track, odds, bwd, by);
        RaceView v = races.view(today, c.race.meet, c.race.rcNo).orElseThrow(IllegalStateException::new);
        String msg = RaceSummary.of(v, publicUrl, by, warn);
        tg.sendMessage(chat, msg, msgId);
        for (Long other : chats) if (other != chat) safeSend(other, msg);
    }

    /** 웹에서 저장했을 때: 허용 대화 전부에 요약 */
    public void notifyAll(String date, int meet, int rc, String by) {
        if (!tg.isEnabled() || chats.isEmpty()) return;
        Thread t = new Thread(() -> {
            try {
                races.view(date, meet, rc).ifPresent(v -> {
                    String msg = RaceSummary.of(v, publicUrl, by, null);
                    for (Long c : chats) safeSend(c, msg);
                });
            } catch (RuntimeException e) {
                log.warn("텔레그램 알림 실패", e);
            }
        }, "telegram-notify");
        t.setDaemon(true);
        t.start();
    }

    private void safeSend(long chat, String msg) {
        try { tg.sendMessage(chat, msg, null); } catch (Exception e) { log.warn("텔레그램 전송 실패 {}: {}", chat, e.getMessage()); }
    }

    // ---------------- 어느 경주인가 ----------------

    static class Hint { Integer meet, rc; String track; LocalDate date; }

    static Hint parseCaption(String s) { return parseCaption(s, LocalDate.now(KST)); }

    /** 캡션: "서울 7", "부경5R", "제주 3경주 다습", "7", "11/15 서울7", "11월 15일 서울 7" */
    static Hint parseCaption(String s, LocalDate today) {
        Hint h = new Hint();
        if (s == null) return h;
        Matcher dm = Pattern.compile("(\\d{1,2})\\s*(?:[/.\\-]|월)\\s*(\\d{1,2})\\s*일?").matcher(s);
        if (dm.find()) {
            try {
                LocalDate d = LocalDate.of(today.getYear(), Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)));
                if (d.isAfter(today.plusDays(7))) d = d.minusYears(1);   // 1월에 "12/28" 처럼 작년 날짜
                h.date = d;
                s = s.substring(0, dm.start()) + " " + s.substring(dm.end());   // 날짜 숫자를 경주 번호로 읽지 않게
            } catch (java.time.DateTimeException ignore) { /* 날짜가 아님 */ }
        }
        for (Map.Entry<String, Integer> e : MEETS.entrySet()) if (s.contains(e.getKey())) { h.meet = e.getValue(); break; }
        Matcher mm = Pattern.compile("(\\d{1,2})\\s*(?:R|r|경주)?").matcher(s);
        if (mm.find()) { int n = Integer.parseInt(mm.group(1)); if (n >= 1 && n <= 20) h.rc = n; }
        for (String t : RaceService.TRACKS) if (s.contains(t)) { h.track = t; break; }
        return h;
    }

    static class Choice {
        final RaceRef race; final String how;
        Choice(RaceRef race, String how) { this.race = race; this.how = how; }
    }

    /**
     * 순서: 캡션 경마장·경주 → 화면의 경마장·경주 번호 → 마명 일치 → 출발 전 가장 가까운 경주.
     * 마릿수(배당을 읽은 수)가 출전마 수와 같은지도 본다.
     */
    static Choice choose(List<RaceRef> cands, ScreenshotReader.Reading r, Hint hint, LocalDateTime now) {
        Integer meet = hint.meet != null ? hint.meet : (r.meetSeen == null ? null : meetOf(r.meetSeen));
        Integer rc = hint.rc != null ? hint.rc : r.raceNoSeen;
        List<RaceRef> pool = new ArrayList<>();
        for (RaceRef c : cands) if ((meet == null || c.meet == meet) && (rc == null || c.rcNo == rc)) pool.add(c);
        if (pool.isEmpty() && rc != null) for (RaceRef c : cands) if (c.rcNo == rc) pool.add(c);   // 경마장을 잘못 읽었을 때
        if (pool.isEmpty()) pool.addAll(cands);

        RaceRef best = null; double bestScore = -1e9; int bestNames = 0;
        for (RaceRef c : pool) {
            int names = 0;
            for (Map.Entry<Integer, String> e : r.names.entrySet())
                if (same(c.names.get(e.getKey()), e.getValue())) names++;
            double score = names * 10;
            int seen = Math.max(r.odds.size(), r.names.size());
            if (seen > 0 && seen == c.names.size()) score += 5;
            LocalDateTime st = start(c);
            if (st != null) {
                long min = Duration.between(now, st).toMinutes();
                if (min >= -3) score += 3 - Math.min(min, 600) / 200.0;   // 출발 전(또는 막 출발), 가까울수록
                else score -= 5 + Math.min(-min, 600) / 200.0;          // 이미 지난 경주
            }
            if (score > bestScore) { bestScore = score; best = c; bestNames = names; }
        }
        if (best == null) return null;
        String how;
        if (hint.meet != null && hint.rc != null) how = "캡션대로 " + best.meetName + " " + best.rcNo + "R";
        else if (pool.size() == 1 && rc != null) how = "화면의 경주 번호로 " + best.meetName + " " + best.rcNo + "R";
        else if (bestNames >= 2) how = "마명 " + bestNames + "개가 맞아 " + best.meetName + " " + best.rcNo + "R";
        else how = "출발 시각이 가장 가까워 " + best.meetName + " " + best.rcNo + "R 로 골랐습니다. 다르면 캡션에 \"부경 5\"처럼 적어 다시 보내 주세요";
        return new Choice(best, how);
    }

    static Integer meetOf(String s) {
        for (Map.Entry<String, Integer> e : MEETS.entrySet()) if (s.contains(e.getKey())) return e.getValue();
        return null;
    }

    static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.replaceAll("\\s", ""), y = b.replaceAll("\\s", "");
        return !x.isEmpty() && (x.equals(y) || (y.length() >= 2 && (x.contains(y) || y.contains(x))));
    }

    static LocalDateTime start(RaceRef c) {
        try {
            String t = c.stTime == null ? "" : c.stTime.trim();
            if (!t.matches("\\d{1,2}:\\d{2}")) return null;
            String[] hm = t.split(":");
            return LocalDate.parse(c.date, DateTimeFormatter.BASIC_ISO_DATE).atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String name(JsonNode from) {
        String n = from.path("first_name").asText("");
        if (n.isEmpty()) n = from.path("username").asText("telegram");
        return n.length() > 20 ? n.substring(0, 20) : n;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
