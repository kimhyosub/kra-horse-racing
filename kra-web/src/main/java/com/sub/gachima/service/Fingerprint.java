package com.sub.gachima.service;

import com.sub.gachima.domain.Entry;
import com.sub.gachima.domain.MeetDay;
import com.sub.gachima.domain.PaperBet;
import com.sub.gachima.domain.Race;
import com.sub.gachima.domain.Repositories.MeetDayRepository;
import com.sub.gachima.domain.Repositories.RaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 기록 지문: 예상을 나중에 고치지 않았다는 걸 누구나 확인할 수 있게, 예상 원문의 SHA-256을 공개한다.
 *  - 전날 예상: 경주일 0시 이후 결과가 나오기 전 처음 본 예상을 원문째 고정 (매시 7분, 서버 시작 때)
 *  - ★ 기록: 출발 전 모의 기록(말 번호·배당·모델 확률)의 지문 — 텔레그램 분석 글에도 같이 올라가 출발 전에 공개된다
 * 원문 형식이 단순해서 아무 SHA-256 계산기로도 같은 값이 나온다.
 */
@Service
public class Fingerprint {
    private static final Logger log = LoggerFactory.getLogger(Fingerprint.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MeetDayRepository meetDays;
    private final RaceRepository races;
    private final Clock clock;
    private final TransactionTemplate tx;

    public Fingerprint(MeetDayRepository meetDays, RaceRepository races, Optional<Clock> clock, PlatformTransactionManager tm) {
        this.meetDays = meetDays; this.races = races;
        this.clock = clock.orElse(Clock.system(KST));
        this.tx = new TransactionTemplate(tm);
    }

    /** gachima-pre|20261127|3|4R|1:5:0.2650,2:9:0.1470,... (전날 예상 순위:말 번호:확률) */
    public static String preText(String date, int meet, Race r) {
        List<Entry> es = new ArrayList<>();
        for (Entry e : r.getEntries()) if (e.getPreRank() > 0) es.add(e);
        if (es.isEmpty()) return null;
        es.sort(Comparator.comparingInt(Entry::getPreRank));
        StringBuilder b = new StringBuilder("gachima-pre|").append(date).append('|').append(meet).append('|').append(r.getRcNo()).append("R|");
        for (int i = 0; i < es.size(); i++) {
            Entry e = es.get(i);
            if (i > 0) b.append(',');
            b.append(e.getPreRank()).append(':').append(e.getChulNo()).append(':').append(String.format(Locale.ROOT, "%.4f", e.getPreP()));
        }
        return b.toString();
    }

    /** gachima-star|20261127|3|4R|5:6.8:0.1720,... (★ 말 번호:기록 배당:모델 확률), 없으면 null */
    public static String starText(String date, int meet, int rcNo, List<PaperBet> bets) {
        if (bets == null || bets.isEmpty()) return null;
        List<PaperBet> l = new ArrayList<>(bets);
        l.sort(Comparator.comparingInt(PaperBet::getChulNo));
        StringBuilder b = new StringBuilder("gachima-star|").append(date).append('|').append(meet).append('|').append(rcNo).append("R|");
        for (int i = 0; i < l.size(); i++) {
            PaperBet x = l.get(i);
            if (i > 0) b.append(',');
            b.append(x.getChulNo()).append(':').append(String.format(Locale.ROOT, "%.1f", x.getBetOdds())).append(':').append(String.format(Locale.ROOT, "%.4f", x.getPModel()));
        }
        return b.toString();
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 화면용 짧은 지문 (앞 12자리) */
    public static String shortHash(String h) { return h == null ? null : h.substring(0, Math.min(12, h.length())); }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() { lockToday(); }

    @Scheduled(cron = "${gachima.fingerprint.cron:0 7 * * * *}", zone = "Asia/Seoul")
    public void hourly() { lockToday(); }

    public void lockToday() {
        String today = LocalDate.now(clock.withZone(KST)).format(DateTimeFormatter.BASIC_ISO_DATE);
        try { tx.execute(st -> lock(today)); }   // 같은 클래스 안 호출이라 @Transactional 대신 직접 트랜잭션
        catch (Exception e) { log.warn("전날 예상 지문 고정 실패: {}", e.toString()); }
    }

    /** 그날 결과가 아직 없는 경주의 전날 예상을 고정 (이미 고정한 경주는 그대로) */
    @Transactional
    public int lock(String date) {
        int n = 0;
        LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
        for (MeetDay md : meetDays.findByRaceDateOrderByMeet(date)) {
            for (Race r : races.findByMeetDayOrderByRcNo(md)) {
                if (r.getPreHash() != null) continue;
                int field = r.getEntries().size();
                boolean finished = r.getEntries().stream().anyMatch(e -> Entry.isResult(e.getFinishOrd(), field) && e.getFinishOrd() < Entry.ORD_OUT);
                if (finished) continue;
                String t = preText(date, md.getMeet(), r);
                if (t == null) continue;
                r.setPreHashText(t); r.setPreHash(sha256(t)); r.setPreHashAt(now);
                n++;
            }
        }
        if (n > 0) log.info("전날 예상 지문 고정 {} {}경주", date, n);
        return n;
    }
}
