package com.sub.gachima.service;

import com.sub.gachima.domain.MeetDay;
import com.sub.gachima.domain.PaperBet;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceRef;
import com.sub.gachima.service.RaceService.RaceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 첫 화면: 가장 가까운 경주일 한눈에 보기 + 지난 경주일엔 얼마나 맞았나.
 *  - 경주마다 가치마가 꼽은 1·2위 (당일 배당이 들어왔으면 당일 분석, 아니면 전날 예상)
 *  - 세 줄 요약: 뚜렷한 경주 수, 가장 뚜렷한 경주, ★
 *  - 영천은 참고 경마장이라 요약 숫자에서 뺀다
 */
@Service
public class HomeService {
    static final int REFERENCE_MEET = 4;
    static final double CLEAR = 0.30;
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RaceService races;
    private final Clock clock;

    public HomeService(RaceService races, Optional<Clock> clock) {
        this.races = races;
        this.clock = clock.orElse(Clock.system(KST));
    }

    public static class Row {
        public int meet, rcNo, dist;
        public String meetName, stTime, date;
        /** 당일 분석이면 true, 전날 예상이면 false */
        public boolean day, finished, hit, top3;
        public EntryView first, second;
        public double p1, p2;
        public final List<EntryView> stars = new ArrayList<>();
        public Integer winNo;
        public String winName;
        public String getP1Text() { return pct(p1); }
        public String getP2Text() { return pct(p2); }
        public int getP1Bar() { return (int) Math.round(Math.min(100, p1 * 100)); }
        public String getSourceLabel() { return day ? "당일" : "전날"; }
        public String getHref() { return "/r/" + date + "/" + meet + "/" + rcNo + (day ? "?mode=day" : ""); }
        public boolean isClear() { return p1 >= CLEAR; }
    }

    public static class MeetBlock {
        public int meet;
        public String name;
        public boolean reference;
        public final List<Row> rows = new ArrayList<>();
    }

    /** 지난 경주일 성적 */
    public static class Past {
        public String date, label;
        public int races, hit, top3, starN, starHit;
        public long starProfit;
        public String getHitRate() { return races == 0 ? "-" : Math.round(100.0 * hit / races) + "%"; }
        public String getTop3Rate() { return races == 0 ? "-" : Math.round(100.0 * top3 / races) + "%"; }
        public String getStarProfitText() { return (starProfit >= 0 ? "+" : "-") + String.format("%,d원", Math.abs(starProfit)); }
        public String getComment() {
            if (races == 0) return "";
            double r = 1.0 * hit / races;
            return r >= 0.40 ? "평소(35% 안팎)보다 잘 맞은 날입니다." : r >= 0.28 ? "평소와 비슷한 날입니다." : "평소보다 이변이 많았던 날입니다.";
        }
    }

    public static class Home {
        public String date, label;
        public boolean upcoming, today;
        public final List<MeetBlock> meets = new ArrayList<>();
        public int races, clear, stars, dayRaces;
        public Row best;
        public Past past;
        public boolean isEmpty() { return meets.isEmpty(); }
        public String getMeetNames() {
            StringBuilder b = new StringBuilder();
            for (MeetBlock m : meets) { if (b.length() > 0) b.append(" · "); b.append(m.name).append(' ').append(m.rows.size()).append("경주"); }
            return b.toString();
        }
        /** 검색용: 사람들이 부르는 경마장 이름 (서울=과천, 부경=부산경남) */
        static String seoMeet(String n) {
            return "서울".equals(n) ? "서울(과천)" : "부경".equals(n) ? "부산경남" : n;
        }
        private String seoMeets() {
            StringBuilder b = new StringBuilder();
            for (MeetBlock m : meets) { if (m.reference) continue; if (b.length() > 0) b.append('·'); b.append(seoMeet(m.name)); }
            return b.length() == 0 ? "서울·부산경남·제주" : b.toString();
        }
        private String shortLabel() {
            LocalDate x = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
            return x.getMonthValue() + "월 " + x.getDayOfMonth() + "일(" + "월화수목금토일".charAt(x.getDayOfWeek().getValue() - 1) + ")";
        }
        public String getSeoTitle() {
            if (date == null) return "오늘의 경마 예상 · 서울(과천)·부산경남·제주 경마 분석 | 가치마";
            return (today ? "오늘의 경마 예상 · " : "경마 예상 · ") + shortLabel() + " " + seoMeets() + " | 가치마";
        }
        public String getSeoDesc() {
            String base = "서울(과천)·부산경남·제주 경마 경주마다 가치마가 꼽은 말과 우승 확률, 당일 배당으로 다시 계산한 ★ 가치마, 지난 경주 적중과 복기를 무료로 봅니다.";
            if (date == null) return base;
            return shortLabel() + " " + seoMeets() + " 경마 예상·분석 (" + getMeetNames() + "). " + base;
        }

        /** 세 줄 요약 */
        public List<String> getSummary() {
            List<String> out = new ArrayList<>();
            if (races == 0) return out;
            out.add(races + "경주 가운데 가치마 1위 확률이 30%를 넘는 경주는 " + clear + "개" + (clear * 2 < races ? "로, 혼전이 많은 날입니다." : "입니다."));
            if (best != null)
                out.add("가장 뚜렷한 경주는 " + best.meetName + " " + best.rcNo + "R — " + best.first.no + "번 " + best.first.name + " " + best.getP1Text() + ".");
            if (dayRaces == 0) out.add(upcoming ? "★ 가치마는 경주 당일 배당이 들어오면 경주마다 표시됩니다. 지금은 전날 예상입니다."
                                                : "이날은 출발 전 당일 배당이 들어온 경주가 없습니다.");
            else out.add("당일 배당이 들어온 " + dayRaces + "경주 중 ★ 가치마 " + stars + "건.");
            return out;
        }
    }

    @Transactional(readOnly = true)
    public Home home() {
        Home h = new Home();
        List<MeetDay> all = races.allMeetDays();
        if (all.isEmpty()) return h;
        TreeSet<String> dates = new TreeSet<>(Comparator.reverseOrder());
        for (MeetDay md : all) dates.add(md.getRaceDate());
        String today = LocalDate.now(clock.withZone(KST)).format(DateTimeFormatter.BASIC_ISO_DATE);
        // 오늘 이후 가장 가까운 경주일, 없으면 가장 최근 경주일
        String pick = null;
        for (String d : dates) if (d.compareTo(today) >= 0) pick = d;
        if (pick == null) pick = dates.first();
        // 오늘 경주가 다 끝났으면(영천 제외, 결과가 다 들어옴) 다음 경주일을 보여 준다
        if (pick.equals(today) && dayFinished(today)) {
            String next = null;
            for (String d : dates) if (d.compareTo(today) > 0) next = d;
            if (next != null) pick = next;
        }
        h.date = pick; h.label = label(pick); h.upcoming = pick.compareTo(today) >= 0; h.today = pick.equals(today);

        Map<Integer, MeetBlock> blocks = new LinkedHashMap<>();
        for (RaceRef ref : races.racesOn(pick)) {
            Optional<RaceView> ov = races.view(pick, ref.meet, ref.rcNo);
            if (!ov.isPresent()) continue;
            Row r = row(ov.get());
            if (r == null) continue;
            MeetBlock b = blocks.computeIfAbsent(ref.meet, k -> {
                MeetBlock x = new MeetBlock(); x.meet = k; x.name = ref.meetName; x.reference = k == REFERENCE_MEET; return x;
            });
            b.rows.add(r);
            if (b.reference) continue;
            h.races++;
            if (r.isClear()) h.clear++;
            if (h.best == null || r.p1 > h.best.p1) h.best = r;
            if (r.day && manual(ov.get())) { h.dayRaces++; h.stars += r.stars.size(); }   // 출발 전 직접 입력한 경주만 (공식 ★)
        }
        // 영천(참고)은 맨 뒤로
        blocks.values().stream().filter(b -> !b.reference).forEach(h.meets::add);
        blocks.values().stream().filter(b -> b.reference).forEach(h.meets::add);

        for (String d : dates) {
            if (d.compareTo(pick) >= 0) continue;
            Past p = past(d);
            if (p.races > 0) { h.past = p; break; }
        }
        return h;
    }

    /** 그날 경주(영천 제외)가 모두 결과가 났나 */
    private boolean dayFinished(String date) {
        int n = 0;
        for (RaceRef ref : races.racesOn(date)) {
            if (ref.meet == REFERENCE_MEET) continue;
            Optional<RaceView> ov = races.view(date, ref.meet, ref.rcNo);
            if (!ov.isPresent()) continue;
            n++;
            Row r = row(ov.get());
            if (r == null || !r.finished) return false;
        }
        return n > 0;
    }

    private static boolean manual(RaceView v) { return RaceService.SRC_MANUAL.equals(v.inputSource); }

    private Row row(RaceView v) {
        boolean day = v.isScored();
        List<EntryView> order = day ? v.dayOrder : v.preOrder;
        if (order == null || order.isEmpty()) return null;
        Row r = new Row();
        r.date = v.date; r.meet = v.meet; r.meetName = v.meetName; r.rcNo = v.rcNo; r.stTime = v.stTime; r.dist = v.dist;
        r.day = day;
        r.first = order.get(0); r.p1 = day ? r.first.p : r.first.preP;
        if (order.size() > 1) { r.second = order.get(1); r.p2 = day ? r.second.p : r.second.preP; }
        if (day && v.values != null) r.stars.addAll(v.values);
        for (EntryView e : v.entries) {
            if (e.ord != null && e.ord == 1) { r.winNo = e.no; r.winName = e.name; }
        }
        r.finished = r.winNo != null;
        if (r.finished) {
            r.hit = r.first.no == r.winNo;
            r.top3 = r.first.ord != null && r.first.ord <= 3;
        }
        return r;
    }

    /** 그날(영천 제외) 결과가 나온 경주: 가치마 1위 우승·3착 안, 출발 전 ★ 모의 기록 손익 */
    private Past past(String date) {
        Past p = new Past();
        p.date = date; p.label = label(date);
        for (RaceRef ref : races.racesOn(date)) {
            if (ref.meet == REFERENCE_MEET) continue;
            Optional<RaceView> ov = races.view(date, ref.meet, ref.rcNo);
            if (!ov.isPresent()) continue;
            Row r = row(ov.get());
            if (r == null || !r.finished) continue;
            p.races++;
            if (r.hit) p.hit++;
            if (r.top3) p.top3++;
            for (PaperBet b : ov.get().paperBets) {
                if (!b.isCounted()) continue;
                p.starN++;
                if (b.isWin()) { p.starHit++; p.starProfit += Math.round((b.getPayout() - 1) * 1000); }
                else p.starProfit -= 1000;
            }
        }
        return p;
    }

    static String pct(double x) { return x <= 0 ? "-" : String.format("%.0f%%", x * 100); }

    static String label(String d) {
        LocalDate x = LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE);
        return x.getYear() + "년 " + x.getMonthValue() + "월 " + x.getDayOfMonth() + "일 (" + "월화수목금토일".charAt(x.getDayOfWeek().getValue() - 1) + ")";
    }
}
