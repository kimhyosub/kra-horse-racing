package com.sub.gachima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.gachima.domain.*;
import com.sub.gachima.domain.Repositories.PaperBetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 모의 기록(장부).
 *  - 기록: 출발 전에 당일 배당을 입력할 때마다 그 경주의 ★ 를 새로 적는다 → 마지막 입력이 공식 기록
 *  - 잠금: 결과가 들어왔거나 출발 시각이 지나면 기록을 바꾸지 않는다
 *  - 정산: 결과가 들어오면 확정 착순·확정 배당으로 (불러오기 때 자동)
 */
@Service
public class PaperService {
    private static final Logger log = LoggerFactory.getLogger(PaperService.class);
    public static final String SRC_WEB = "web", SRC_MIGRATED = "migrated";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PaperBetRepository bets;
    private final Repositories.MeetDayRepository meetDays;
    private final ObjectMapper om;
    private final Clock clock;

    public PaperService(PaperBetRepository bets, Repositories.MeetDayRepository meetDays, ObjectMapper om, Optional<Clock> clock) {
        this.bets = bets; this.meetDays = meetDays; this.om = om; this.clock = clock.orElse(Clock.system(KST));
    }

    public enum Outcome { RECORDED, LOCKED_RESULT, LOCKED_TIME }

    /** 출발 시각이 지났거나 결과가 있으면 잠김 */
    public Outcome lockState(Race race) {
        for (Entry e : race.getEntries()) if (e.getFinishOrd() != null) return Outcome.LOCKED_RESULT;
        LocalDateTime start = startTime(race);
        if (start != null && !LocalDateTime.now(clock.withZone(KST)).isBefore(start)) return Outcome.LOCKED_TIME;
        return Outcome.RECORDED;
    }

    static LocalDateTime startTime(Race race) {
        try {
            LocalDate d = LocalDate.parse(race.getMeetDay().getRaceDate(), DateTimeFormatter.BASIC_ISO_DATE);
            String t = race.getStTime() == null ? "" : race.getStTime().trim();
            if (!t.matches("\\d{1,2}:\\d{2}")) return null;
            String[] hm = t.split(":");
            return d.atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 당일 입력 한 번 → 그 경주의 기록을 이번 ★ 로 바꿔 쓴다 */
    @Transactional
    public Outcome record(Race race, List<DayModel.Result> results, String track, Map<Integer, Double> bwd, String user) {
        Outcome o = lockState(race);
        if (o != Outcome.RECORDED) return o;
        String date = race.getMeetDay().getRaceDate();
        int meet = race.getMeetDay().getMeet();
        List<PaperBet> old = bets.findByRaceDateAndMeetAndRcNoOrderByChulNo(date, meet, race.getRcNo());
        bets.deleteAll(old);
        bets.flush();
        Map<Integer, String> names = new HashMap<>();
        for (Entry e : race.getEntries()) names.put(e.getChulNo(), e.getHrName());
        LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
        for (DayModel.Result r : results) {
            if (!r.value) continue;
            PaperBet b = new PaperBet();
            b.setRaceDate(date); b.setMeet(meet); b.setRcNo(race.getRcNo()); b.setChulNo(r.no);
            b.setHrName(names.get(r.no)); b.setBetOdds(r.odds); b.setPModel(r.pModel); b.setEv(r.ev);
            b.setBwd(bwd.get(r.no)); b.setTrack(track); b.setPlacedAt(now); b.setSource(SRC_WEB); b.setPlacedBy(user);
            bets.save(b);
        }
        return o;
    }

    /** 결과가 들어온 경주의 기록을 정산 (ImportService 가 경주를 저장한 뒤 부른다) */
    @Transactional
    public void settle(Race race) {
        Map<Integer, Entry> byNo = new HashMap<>();
        boolean hasResult = false;
        for (Entry e : race.getEntries()) { byNo.put(e.getChulNo(), e); if (e.getFinishOrd() != null) hasResult = true; }
        if (!hasResult) return;
        for (PaperBet b : bets.findByRaceDateAndMeetAndRcNoOrderByChulNo(race.getMeetDay().getRaceDate(), race.getMeetDay().getMeet(), race.getRcNo())) {
            Entry e = byNo.get(b.getChulNo());
            b.setSettled(true);
            b.setFinishOrd(e == null ? null : e.getFinishOrd());
            b.setFinalOdds(e == null ? null : e.getFinalOdds());
            bets.save(b);
        }
    }

    public List<PaperBet> forRace(Race race) {
        return bets.findByRaceDateAndMeetAndRcNoOrderByChulNo(race.getMeetDay().getRaceDate(), race.getMeetDay().getMeet(), race.getRcNo());
    }

    /** paper-*.json: 예전 모의배팅 기록 옮기기. 같은 날짜·경마장·경주·마번은 덮어쓴다 */
    @Transactional
    public int importFile(File file) throws IOException {
        JsonNode doc = om.readTree(file);
        int n = 0;
        for (JsonNode x : doc.path("bets")) {
            String date = x.path("date").asText();
            int meet = x.path("meet").asInt(), rc = x.path("rc").asInt(), no = x.path("no").asInt();
            if (date.length() != 8 || meet == 0 || rc == 0 || no == 0) throw new IOException("잘못된 기록: " + x);
            PaperBet b = bets.findByRaceDateAndMeetAndRcNoAndChulNo(date, meet, rc, no).orElseGet(PaperBet::new);
            b.setRaceDate(date); b.setMeet(meet); b.setRcNo(rc); b.setChulNo(no);
            b.setHrName(x.path("name").asText(null));
            b.setBetOdds(x.path("odds").asDouble());
            b.setPModel(x.path("pModel").asDouble()); b.setEv(x.path("ev").asDouble());
            b.setBwd(x.path("bwd").isNumber() ? x.path("bwd").asDouble() : null);
            b.setTrack(x.path("track").asText(null));
            String at = x.path("placedAt").asText("");
            b.setPlacedAt(at.isEmpty() ? null : LocalDateTime.parse(at));
            b.setSource(x.path("source").asText(SRC_MIGRATED));
            b.setSettled(x.path("settled").asBoolean(false));
            b.setFinishOrd(x.path("finishOrd").isNumber() ? x.path("finishOrd").asInt() : null);
            b.setFinalOdds(x.path("finalOdds").isNumber() ? x.path("finalOdds").asDouble() : null);
            bets.save(b);
            n++;
        }
        log.info("모의 기록 불러옴 {}: {}건", file.getName(), n);
        return n;
    }

    // ---------------- 장부 ----------------

    @Transactional(readOnly = true)
    public Ledger ledger() {
        Ledger l = new Ledger();
        l.bets = bets.findAllByOrderByRaceDateDescMeetAscRcNoAscChulNoAsc();
        Set<String> days = new HashSet<>();
        for (MeetDay md : meetDays.findAll()) days.add(md.getRaceDate() + "-" + md.getMeet());
        for (PaperBet b : l.bets) b.setLinked(days.contains(b.getRaceDate() + "-" + b.getMeet()));
        Map<Integer, Row> byMeet = new TreeMap<>();
        Row official = new Row("공식 합계 (영천 제외)");
        official.total = true;
        for (PaperBet b : l.bets) {
            byMeet.computeIfAbsent(b.getMeet(), k -> new Row(b.isOfficial() ? b.getMeetName() : b.getMeetName() + " (참고)")).add(b);
            if (b.isOfficial()) official.add(b);
        }
        l.rows.addAll(byMeet.values());
        if (official.n + official.pending > 0) l.rows.add(official);
        return l;
    }

    public static class Ledger {
        public List<PaperBet> bets = new ArrayList<>();
        public final List<Row> rows = new ArrayList<>();
        public boolean isEmpty() { return bets.isEmpty(); }
    }

    public static class Row {
        public final String name;
        public boolean total;
        /** n: 정산된 건수(취소 제외) */
        public int n, wins, pending, scratched;
        public double ret, oddsSum;

        Row(String name) { this.name = name; }

        void add(PaperBet b) {
            if (!b.isSettled()) { pending++; return; }
            if (b.isScratched()) { scratched++; return; }
            n++; oddsSum += b.getBetOdds();
            if (b.isWin()) { wins++; ret += b.getPayout(); }
        }

        public String getHitRate() { return n == 0 ? "-" : String.format("%.1f%%", 100.0 * wins / n); }
        public String getRoi() { return n == 0 ? "-" : String.format("%.0f%%", 100 * ret / n); }
        public String getProfit() { return n == 0 ? "-" : String.format("%+.1f", ret - n); }
        public String getAvgOdds() { return n == 0 ? "-" : String.format("%.1f", oddsSum / n); }
        public boolean isUp() { return n > 0 && ret >= n; }
    }
}
