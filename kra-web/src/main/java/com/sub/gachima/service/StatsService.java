package com.sub.gachima.service;

import com.sub.gachima.domain.*;
import com.sub.gachima.domain.Repositories.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

/**
 * 성적: 결과가 들어온 경주만 센다.
 *  - 전날 예상: 1위 적중, 우승마가 상위 5두 안, 상위 5두 중 3착 안에 든 수
 *  - 시장 1번 인기(최종 배당 최저) 우승률: 비교 기준
 *  - 가치마(최종 배당): 확정 최종 배당·마체중·주로로 당일 모델을 다시 돌린 결과 (사후 검증)
 *  - 모의 기록(경주 전 입력 ★)은 PaperService 장부에서 따로 센다
 * 영천은 참고 기록이라 공식 합계에서 뺀다.
 */
@Service
public class StatsService {

    public static final int REFERENCE_MEET = 4;

    /** 당일 분석 1·2·3위를 그대로 샀을 때의 승식 (복승 1-2, 쌍승 1→2, 삼복승 1-2-3, 삼쌍승 1→2→3) */
    public static final String[] POOLS = {"단승", "복승", "쌍승", "삼복승", "삼쌍승"};
    static final String[] POOL_RULE = {"당일 1위", "1·2위 (순서 무관)", "1위→2위", "1·2·3위 (순서 무관)", "1위→2위→3위"};
    /** 과거 검증 (2025~2026, 서울·부경·제주 3,969경주, 학습에 안 쓴 기간, 확정 배당 기준): 적중률 / 회수율 % */
    static final double[] BACK_HIT = {37.1, 17.8, 10.5, 10.7, 2.9};
    static final double[] BACK_ROI = {82.7, 83.1, 78.0, 79.5, 67.6};
    private static final ObjectMapper OM = new ObjectMapper();

    private final MeetDayRepository meetDays;
    private final RaceRepository races;
    private final RaceService raceService;

    public StatsService(MeetDayRepository meetDays, RaceRepository races, RaceService raceService) {
        this.meetDays = meetDays; this.races = races; this.raceService = raceService;
    }

    @Transactional(readOnly = true)
    public Stats compute() {
        Stats s = new Stats();
        Map<Integer, Tally> byMeet = new TreeMap<>();
        Tally official = new Tally("공식 합계 (영천 제외)");
        official.total = true;
        for (MeetDay md : meetDays.findAllByOrderByRaceDateDescMeetAsc()) {
            DayModel model = raceService.model(md);
            Tally day = new Tally(md.getMeetName());
            day.date = md.getRaceDate(); day.meet = md.getMeet();
            for (Race r : races.findByMeetDayOrderByRcNo(md)) count(day, r, model);
            if (day.races == 0) continue;
            s.days.add(day);
            byMeet.computeIfAbsent(md.getMeet(), k -> new Tally(md.getMeet() == REFERENCE_MEET ? md.getMeetName() + " (참고)" : md.getMeetName()))
                  .add(day);
            if (md.getMeet() != REFERENCE_MEET) official.add(day);
        }
        s.meets.addAll(byMeet.values());
        if (official.races > 0) s.meets.add(official);
        return s;
    }

    private void count(Tally t, Race r, DayModel model) {
        List<Entry> es = r.getEntries();
        Entry win = null, fav = null;
        for (Entry e : es) {
            if (e.getFinishOrd() != null && e.getFinishOrd() == 1 && win == null) win = e;
            if (e.getFinalOdds() != null && (fav == null || e.getFinalOdds() < fav.getFinalOdds())) fav = e;
        }
        if (win == null) return;   // 결과 전
        RaceRec rec = new RaceRec();
        rec.date = r.getMeetDay().getRaceDate(); rec.meet = r.getMeetDay().getMeet(); rec.meetName = r.getMeetDay().getMeetName();
        rec.rcNo = r.getRcNo(); rec.official = rec.meet != REFERENCE_MEET;
        Map<Integer, Integer> at = new HashMap<>();
        for (Entry e : es) if (e.getFinishOrd() != null && e.getFinishOrd() <= 3 && !at.containsKey(e.getFinishOrd())) at.put(e.getFinishOrd(), e.getChulNo());
        rec.a = at.get(1); rec.b = at.get(2); rec.c = at.get(3);
        List<Entry> byPre = new ArrayList<>(es);
        byPre.sort(Comparator.comparingInt(Entry::getPreRank));
        for (Entry e : byPre) if (rec.preTop5.size() < 5) rec.preTop5.add(e.getChulNo());
        Map<Integer, Integer> pr = new HashMap<>();
        for (Entry e : es) pr.put(e.getChulNo(), e.getPreRank());
        rec.pre1 = win.getPreRank() == 1;
        if (rec.b != null) {
            rec.preQ = new HashSet<>(Arrays.asList(rec.a, rec.b)).equals(new HashSet<>(rec.preTop5.subList(0, Math.min(2, rec.preTop5.size()))));
            rec.in512 = pr.getOrDefault(rec.a, 99) <= 5 && pr.getOrDefault(rec.b, 99) <= 5;
            if (rec.c != null) rec.in5123 = rec.in512 && pr.getOrDefault(rec.c, 99) <= 5;
        }
        for (Entry e : es) if (e.getFinishOrd() != null && e.getFinishOrd() <= 3 && e.getPreRank() <= 5) rec.top3In5++;
        if (fav != null) { rec.favNo = fav.getChulNo(); rec.fav1 = fav.getChulNo() == win.getChulNo(); }
        try {
            if (r.getDivJson() != null) {
                JsonNode dv = OM.readTree(r.getDivJson());
                if (dv.path("복승").isNumber()) rec.qDiv = dv.path("복승").asDouble();
                if (dv.path("쌍승").isNumber()) rec.eDiv = dv.path("쌍승").asDouble();
                if (dv.path("복연").isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> it = dv.path("복연").fields();
                    while (it.hasNext()) { Map.Entry<String, JsonNode> e = it.next(); if (e.getValue().isNumber()) rec.bDiv.put(e.getKey(), e.getValue().asDouble()); }
                }
            }
        } catch (Exception ignore) { }
        t.recs.add(rec);
        t.races++;
        if (win.getPreRank() == 1) t.pre1++;
        if (win.getPreRank() <= 5) t.preTop5++;
        for (Entry e : es) if (e.getFinishOrd() != null && e.getFinishOrd() <= 3 && e.getPreRank() <= 5) t.top3In5++;
        if (fav != null) { t.favRaces++; if (fav.getChulNo() == win.getChulNo()) t.favWin++; }

        final Entry w = win;
        // 당일 분석 1위 적중: 출발 전 직접 입력이 있으면 그것, 없으면 확정 배당으로 계산한 당일 1위
        Optional<DayInput> dayIn = raceService.input(r, RaceService.SRC_MANUAL);
        final boolean afterRace = !dayIn.isPresent();
        if (!dayIn.isPresent()) dayIn = raceService.input(r, RaceService.SRC_FINAL);
        dayIn.ifPresent(in -> {
            List<DayModel.Result> rs = new ArrayList<>(model.score(raceService.horses(r, in), in.getTrack()));
            rs.sort((x, y) -> Double.compare(y.pModel, x.pModel));
            if (rs.isEmpty()) return;
            t.dayRaces++;
            if (rs.get(0).no == w.getChulNo()) t.day1++;
            RaceRec rc = t.recs.get(t.recs.size() - 1);
            rc.dayNo = rs.get(0).no; rc.day1 = rs.get(0).no == w.getChulNo(); rc.dayAfter = afterRace;
            for (DayModel.Result x : rs) if (rc.dayTop5.size() < 5) rc.dayTop5.add(x.no);
            for (DayModel.Result x : rs) rc.probs.add(new double[]{x.pModel, x.no == w.getChulNo() ? 1 : 0});
            if (rc.b != null && rs.size() >= 2) {
                rc.dayPair = new HashSet<>(Arrays.asList(rc.a, rc.b)).equals(new HashSet<>(Arrays.asList(rs.get(0).no, rs.get(1).no)));
                rc.dayIn12 = rc.dayTop5.contains(rc.a) && rc.dayTop5.contains(rc.b);
                if (rc.c != null) rc.dayIn123 = rc.dayIn12 && rc.dayTop5.contains(rc.c);
            }
            for (Integer n : rc.getResult()) if (rc.dayTop5.contains(n)) rc.dayTop3In5++;
            for (DayModel.Result x : rs) if (x.value) { rc.stars.add(x.no); if (x.no == w.getChulNo()) rc.starHit = true; }
            pools(t, r, rs);
        });
        raceService.input(r, RaceService.SRC_FINAL).ifPresent(in -> {
            for (DayModel.Result x : model.score(raceService.horses(r, in), in.getTrack())) {
                if (!x.value) continue;
                t.finN++;
                if (x.no == w.getChulNo()) { t.finWin++; t.finRet += w.getFinalOdds() != null ? w.getFinalOdds() : x.odds; }
            }
        });
    }

    /** 당일 1·2·3위 조합이 실제 착순과 맞았나 → 맞으면 그 승식 확정 배당을 받는다. 배당이 아직 없는 경주는 그 승식에서 뺀다 */
    private void pools(Tally t, Race r, List<DayModel.Result> rs) {
        if (rs.size() < 3) return;
        Map<Integer, Integer> at = new HashMap<>();
        for (Entry e : r.getEntries()) if (e.getFinishOrd() != null && e.getFinishOrd() <= 3) at.put(e.getFinishOrd(), e.getChulNo());
        if (at.size() < 3 || !at.containsKey(1) || !at.containsKey(2) || !at.containsKey(3)) return;   // 동착 등
        int a = at.get(1), b = at.get(2), c = at.get(3);
        int x = rs.get(0).no, y = rs.get(1).no, z = rs.get(2).no;
        JsonNode div = null;
        try { if (r.getDivJson() != null) div = OM.readTree(r.getDivJson()); } catch (Exception ignore) { }
        Set<Integer> top2 = new HashSet<>(Arrays.asList(a, b)), top3 = new HashSet<>(Arrays.asList(a, b, c));
        boolean[] hit = {
                x == a,
                new HashSet<>(Arrays.asList(x, y)).equals(top2),
                x == a && y == b,
                new HashSet<>(Arrays.asList(x, y, z)).equals(top3),
                x == a && y == b && z == c};
        for (int i = 0; i < POOLS.length; i++) {
            Double od = div != null && div.path(POOLS[i]).isNumber() ? div.path(POOLS[i]).asDouble() : null;
            if (od == null && i == 0) {   // 단승은 확정 배당으로 대신
                for (Entry e : r.getEntries()) if (e.getChulNo() == a) od = e.getFinalOdds();
            }
            if (od == null) continue;
            t.poolN[i]++;
            if (hit[i]) { t.poolHit[i]++; t.poolRet[i] += od; }
        }
    }

    public static class PoolRow {
        public final String name, rule;
        public final int n, hit;
        public final double ret, backHit, backRoi;
        PoolRow(int i, int n, int hit, double ret) {
            this.name = POOLS[i]; this.rule = POOL_RULE[i]; this.n = n; this.hit = hit; this.ret = ret;
            this.backHit = BACK_HIT[i]; this.backRoi = BACK_ROI[i];
        }
        public String getHitRate() { return n == 0 ? "-" : String.format("%.1f%%", 100.0 * hit / n); }
        public String getRoi() { return n == 0 ? "-" : String.format("%.0f%%", 100.0 * ret / n); }
        public boolean isUp() { return n > 0 && ret >= n; }
        public String getProfit() { return n == 0 ? "-" : String.format("%+.1f", ret - n); }
        public String getBack() { return String.format("%.1f%% / %.0f%%", backHit, backRoi); }
    }

    /** 결과가 나온 경주 하나: 전날 예상 상위 5두와 실제 1·2·3착, 당일 1위, 인기 1위, ★ */
    public static class RaceRec {
        public String date, meetName;
        public int meet, rcNo, top3In5;
        public boolean official, pre1;
        public Integer a, b, c, favNo, dayNo;
        public Boolean preQ, in512, in5123, fav1, day1, dayPair, dayIn12, dayIn123;
        public int dayTop3In5;
        /** 당일 분석의 말별 {모델 확률, 우승 1/0} (확률 보정표용) */
        public final List<double[]> probs = new ArrayList<>();
        /** 실제 1·2착 조합의 복승·쌍승 확정 배당 (밤 수집 뒤) */
        public Double qDiv, eDiv;
        /** 복연승 적중 조합(1-2·1-3·2-3착) 확정 배당 {"1-6": 3.2, …} */
        public final Map<String, Double> bDiv = new HashMap<>();
        /** 박스로 사는 당일 상위 k두 */
        public List<Integer> top(int k) { return dayTop5.subList(0, Math.min(k, dayTop5.size())); }
        private boolean both(int k, int x, int y) { List<Integer> t = top(k); return t.contains(x) && t.contains(y); }
        private static int pairs(int n) { return n * (n - 1) / 2; }
        /** 복승·쌍승 박스를 계산할 수 있나: 당일 분석 + 1·2착 + 복승·쌍승 배당 */
        public boolean isBox() { return isDay() && b != null && qDiv != null && eDiv != null && dayTop5.size() >= 2; }
        /** 복연 박스: 1·2·3착이 있고 복연 배당 셋이 다 있어야 계산 */
        public boolean isBoxB() { return isDay() && c != null && bDiv.size() == 3 && dayTop5.size() >= 2; }
        public boolean isAnyBox() { return isBox() || isBoxB(); }
        public boolean boxHit(int k) { return isBox() && both(k, a, b); }
        public long boxQCost(int k) { return pairs(top(k).size()) * 1000L; }
        public long boxECost(int k) { return pairs(top(k).size()) * 2000L; }
        public long boxBCost(int k) { return pairs(top(k).size()) * 1000L; }
        public long boxQRet(int k) { return boxHit(k) ? Math.round(qDiv * 1000) : 0; }
        public long boxERet(int k) { return boxHit(k) ? Math.round(eDiv * 1000) : 0; }
        public long boxBRet(int k) {
            long s = 0;
            for (Map.Entry<String, Double> e : bDiv.entrySet()) {
                String[] p = e.getKey().split("-");
                if (both(k, Integer.parseInt(p[0]), Integer.parseInt(p[1]))) s += Math.round(e.getValue() * 1000);
            }
            return s;
        }
        /** k두 박스(복승·쌍승·복연 1,000원씩) 손익 */
        public long boxProfit(int k) {
            long v = 0;
            if (isBox()) v += boxQRet(k) + boxERet(k) - boxQCost(k) - boxECost(k);
            if (isBoxB()) v += boxBRet(k) - boxBCost(k);
            return v;
        }
        public String boxProfitText(int k) { return won(boxProfit(k)); }
        /** 출발 전 배당 입력이 없어 경주 후 확정 배당으로 계산한 당일 분석 */
        public boolean dayAfter;
        public final List<Integer> dayTop5 = new ArrayList<>();
        public boolean isDay() { return day1 != null; }
        /** 경주별 비교에 보여줄 예상 5두: 당일 분석이 있으면 당일, 없으면 전날 */
        public List<Integer> getShownTop5() { return isDay() ? dayTop5 : preTop5; }
        public int getShownTop3In5() { return isDay() ? dayTop3In5 : top3In5; }
        public boolean starHit;
        public final List<Integer> preTop5 = new ArrayList<>(), stars = new ArrayList<>();
        public List<Integer> getResult() {
            List<Integer> l = new ArrayList<>();
            if (a != null) l.add(a); if (b != null) l.add(b); if (c != null) l.add(c);
            return l;
        }
        public boolean isIn5(int no) { return preTop5.contains(no); }
        public boolean isStarAny() { return !stars.isEmpty(); }
    }

    /** 정확도 묶음: 경주 수와 각 지표 적중 수 */
    public static class Agg {
        public int n, pre1, q, qN, in12, in12N, in123, in123N, dayN, day1, favN, fav1, starRaces, starHit, top3In5;
        /** 당일 분석이 있던 경주만: 1·2위 동시, 5두 안 1·2착·1·2·3착, 같은 경주의 인기 1위·전날 1위 */
        public int dq, dqN, din12, din12N, din123, din123N, dTop3In5, dFav1, dFavN, dPre1;
        /** 당일 5두 복승·쌍승 박스 (1,000원씩): 경주 수, 적중, 산 돈, 받은 돈 */
        public int boxN, boxHit;
        /** 박스로 사는 당일 상위 두수 (3·4·5) */
        public int boxK = 5;
        public int getPairs() { return boxK * (boxK - 1) / 2; }
        public long qCost, qRet, eCost, eRet;
        public int bN, bHit;
        public long bCost, bRet;
        void add(RaceRec r) {
            n++; if (r.pre1) pre1++;
            if (r.preQ != null) { qN++; if (r.preQ) q++; }
            if (r.in512 != null) { in12N++; if (r.in512) in12++; }
            if (r.in5123 != null) { in123N++; if (r.in5123) in123++; }
            if (r.day1 != null) { dayN++; if (r.day1) day1++; }
            if (r.fav1 != null) { favN++; if (r.fav1) fav1++; }
            if (r.isStarAny()) { starRaces++; if (r.starHit) starHit++; }
            top3In5 += r.top3In5;
            if (r.isDay()) {
                if (r.dayPair != null) { dqN++; if (r.dayPair) dq++; }
                if (r.dayIn12 != null) { din12N++; if (r.dayIn12) din12++; }
                if (r.dayIn123 != null) { din123N++; if (r.dayIn123) din123++; }
                dTop3In5 += r.dayTop3In5;
                if (r.fav1 != null) { dFavN++; if (r.fav1) dFav1++; }
                if (r.pre1) dPre1++;
            }
            if (r.isBox()) {
                boxN++;
                qCost += r.boxQCost(boxK); eCost += r.boxECost(boxK);
                if (r.boxHit(boxK)) { boxHit++; qRet += r.boxQRet(boxK); eRet += r.boxERet(boxK); }
            }
            if (r.isBoxB()) {
                bN++; bCost += r.boxBCost(boxK);
                long got = r.boxBRet(boxK);
                bRet += got; if (got > 0) bHit++;
            }
        }
        public String getBoxHitRate() { return pct(boxHit, boxN); }
        public String getQProfit() { return won(qRet - qCost); }
        public String getEProfit() { return won(eRet - eCost); }
        public String getBoxProfit() { return won(qRet + eRet + bRet - qCost - eCost - bCost); }
        public String getBProfit() { return won(bRet - bCost); }
        public String getBRoi() { return roi(bRet, bCost); }
        public boolean isBUp() { return bRet >= bCost; }
        public String getBCostText() { return won0(bCost); }
        public String getBHitRate() { return pct(bHit, bN); }
        public boolean isAnyBox() { return boxN + bN > 0; }
        public String getQRoi() { return roi(qRet, qCost); }
        public String getERoi() { return roi(eRet, eCost); }
        public String getBoxRoi() { return roi(qRet + eRet + bRet, qCost + eCost + bCost); }
        public boolean isQUp() { return qRet >= qCost; }
        public boolean isEUp() { return eRet >= eCost; }
        public boolean isBoxUp() { return qRet + eRet + bRet >= qCost + eCost + bCost; }
        public String getQCostText() { return won0(qCost); }
        public String getECostText() { return won0(eCost); }
        public String getBoxCostText() { return won0(qCost + eCost + bCost); }
        public String getBoxRetText() { return won0(qRet + eRet + bRet); }
        private static String roi(long ret, long cost) { return cost == 0 ? "-" : String.format("%.0f%%", 100.0 * ret / cost); }
        public String getDayPairRate() { return pct(dq, dqN); }
        public String getDayIn12Rate() { return pct(din12, din12N); }
        public String getDayIn123Rate() { return pct(din123, din123N); }
        public String getDayFav1Rate() { return pct(dFav1, dFavN); }
        public String getDayPre1Rate() { return pct(dPre1, dayN); }
        public String getDayTop3In5Avg() { return dayN == 0 ? "-" : String.format("%.2f", (double) dTop3In5 / dayN); }
        public String getPre1Rate() { return pct(pre1, n); }
        public String getPairRate() { return pct(q, qN); }
        public String getIn12Rate() { return pct(in12, in12N); }
        public String getIn123Rate() { return pct(in123, in123N); }
        public String getDay1Rate() { return pct(day1, dayN); }
        public String getFav1Rate() { return pct(fav1, favN); }
        public String getTop3In5Avg() { return n == 0 ? "-" : String.format("%.2f", (double) top3In5 / n); }
        static Double rate(int a, int b) { return b == 0 ? null : 100.0 * a / b; }
        private static String pct(int a, int b) { return b == 0 ? "-" : String.format("%.1f%%", 100.0 * a / b); }
    }

    /** 검증 리포트: 고른 날, 최근 20경주일 누적, 날짜별 추이, 경주별 비교 */
    public static class Report {
        public final List<String> dates = new ArrayList<>();
        public String date;
        public final Agg day = new Agg(), rolling = new Agg();
        /** 박스 두수: 3·4·5 */
        public int boxK = 5;
        /** 과거 2년(2025~2026, 3,969경주) 같은 박스의 승식별 회수율 */
        public String getBoxBack() {
            return boxK == 3 ? "복승 81% · 쌍승 80% · 복연 80%" : boxK == 4 ? "복승 77% · 쌍승 78% · 복연 79%" : "복승 74% · 쌍승 76% · 복연 78%";
        }
        public int rollingDays;
        /** 전체 기간: 가치마 당일 1위 vs 배당 1위, 확률 보정표 */
        public final Versus versus = new Versus();
        public final List<CalibRow> calib = new ArrayList<>();
        public int calibHorses;
        public boolean isHasCalib() { return calibHorses > 0; }
        public final List<RaceRec> races = new ArrayList<>();
        public final List<String> trendDates = new ArrayList<>();
        public final List<Agg> trend = new ArrayList<>();
        public boolean isEmpty() { return dates.isEmpty(); }
        public static String label(String d) {
            java.time.LocalDate x = java.time.LocalDate.parse(d, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            return x.getMonthValue() + "월 " + x.getDayOfMonth() + "일 (" + "월화수목금토일".charAt(x.getDayOfWeek().getValue() - 1) + ")";
        }
        public String getDateLabel() { return date == null ? "" : label(date); }
        public String labelOf(String d) { return label(d); }
        public String getFirstDate() { return trendDates.isEmpty() ? "" : trendDates.get(0); }
        public Chart chart(String metric) { return Chart.of(this, metric); }
    }

    /** 가치마 당일 1위와 배당(시장) 1위의 우승률 비교. 같은 경주만 센다 */
    public static class Versus {
        public int n, ours, market, same, oursOnly, marketOnly;
        /** 과거 2년(2025~2026, 3,969경주, 확정 배당) */
        public String getBackOurs() { return "37.1%"; }
        public String getBackMarket() { return "37.4%"; }
        public String getBackOursRoi() { return "83%"; }
        public String getBackMarketRoi() { return "81%"; }
        void add(RaceRec r) {
            if (r.day1 == null || r.fav1 == null) return;
            n++;
            if (r.day1) ours++;
            if (r.fav1) market++;
            if (r.dayNo != null && r.dayNo.equals(r.favNo)) same++;
            else { if (r.day1) oursOnly++; if (r.fav1) marketOnly++; }
        }
        static String pct(int a, int b) { return b == 0 ? "-" : String.format("%.1f%%", 100.0 * a / b); }
        public String getOursRate() { return pct(ours, n); }
        public String getMarketRate() { return pct(market, n); }
        public int getOursBar() { return n == 0 ? 0 : (int) Math.round(100.0 * ours / n); }
        public int getMarketBar() { return n == 0 ? 0 : (int) Math.round(100.0 * market / n); }
        public int getDiff() { return n - same; }
        public String getSameRate() { return pct(same, n); }
        public String getVerdict() {
            if (n < 30) return "아직 " + n + "경주라 차이를 판단하기 이릅니다.";
            int d = ours - market;
            return d > 0 ? "가치마 1위가 " + d + "경주 더 이겼습니다." : d < 0 ? "배당 1위가 " + (-d) + "경주 더 이겼습니다." : "두 방식의 우승 수가 같습니다.";
        }
    }

    /** 확률 구간별: 가치마가 매긴 우승 확률 평균 vs 실제 우승 비율 */
    public static class CalibRow {
        public final String label;
        public int n, wins;
        public double pSum;
        CalibRow(String label) { this.label = label; }
        public String getPred() { return n == 0 ? "-" : String.format("%.1f%%", 100 * pSum / n); }
        public String getActual() { return n == 0 ? "-" : String.format("%.1f%%", 100.0 * wins / n); }
        public int getPredBar() { return n == 0 ? 0 : (int) Math.round(Math.min(100, 100 * pSum / n * 2)); }
        public int getActualBar() { return n == 0 ? 0 : (int) Math.round(Math.min(100, 100.0 * wins / n * 2)); }
        /** 실제가 예상보다 높으면 +, 낮으면 - (%p) */
        public String getGap() { return n == 0 ? "" : String.format("%+.1f%%p", 100.0 * wins / n - 100 * pSum / n); }
        public boolean isThin() { return n < 30; }
    }
    static final double[] CALIB_EDGES = {0, .05, .10, .15, .20, .30, .40, .50, 1.01};
    static final String[] CALIB_LABELS = {"5% 미만", "5~10%", "10~15%", "15~20%", "20~30%", "30~40%", "40~50%", "50% 이상"};

    /** 추이 선 그래프용 좌표 (SVG viewBox 0 0 640 250, 그림 영역 x 40~628, y 16~216 = 100%~0%) */
    public static class Chart {
        public static final int X0 = 40, X1 = 628, Y0 = 16, Y1 = 216;
        public final List<Series> series = new ArrayList<>();
        public final List<String> xLabels = new ArrayList<>();
        public final List<Integer> xs = new ArrayList<>();
        static Chart of(Report r, String metric) {
            Chart c = new Chart();
            int n = r.trend.size();
            for (int i = 0; i < n; i++) {
                c.xs.add(n == 1 ? (X0 + X1) / 2 : X0 + (X1 - X0) * i / (n - 1));
                String d = r.trendDates.get(i);
                c.xLabels.add(Integer.parseInt(d.substring(4, 6)) + "." + Integer.parseInt(d.substring(6)));
            }
            if ("win".equals(metric)) {
                c.add("당일 1위", "s-pre", r, x -> Agg.rate(x.day1, x.dayN));
                c.add("인기 1위", "s-fav", r, x -> Agg.rate(x.dFav1, x.dFavN));
                c.add("전날 1위", "s-day", r, x -> Agg.rate(x.dPre1, x.dayN));
            } else {
                c.add("5두 안 1·2착", "s-pre", r, x -> Agg.rate(x.din12, x.din12N));
                c.add("5두 안 1·2·3착", "s-day", r, x -> Agg.rate(x.din123, x.din123N));
            }
            return c;
        }
        void add(String name, String cls, Report r, java.util.function.Function<Agg, Double> f) {
            Series s = new Series(name, cls);
            StringBuilder pts = new StringBuilder();
            for (int i = 0; i < r.trend.size(); i++) {
                Double v = f.apply(r.trend.get(i));
                if (v == null) continue;
                int y = (int) Math.round(Y1 - (Y1 - Y0) * v / 100.0);
                s.dots.add(new Dot(xs.get(i), y, xLabels.get(i) + " " + name + " " + String.format("%.1f%%", v) + " (" + countText(r.trend.get(i), name) + ")"));
                if (pts.length() > 0) pts.append(' ');
                pts.append(xs.get(i)).append(',').append(y);
            }
            s.points = pts.toString();
            if (!s.dots.isEmpty()) series.add(s);
        }
        static String countText(Agg a, String name) {
            if (name.startsWith("전날")) return a.dPre1 + "/" + a.dayN;
            if (name.startsWith("당일")) return a.day1 + "/" + a.dayN;
            if (name.startsWith("인기")) return a.dFav1 + "/" + a.dFavN;
            if (name.contains("1·2·3")) return a.din123 + "/" + a.din123N;
            return a.din12 + "/" + a.din12N;
        }
        /** x 축 날짜 글자를 몇 칸마다 찍을지 (겹치지 않게) */
        public int getLabelStep() { return Math.max(1, (int) Math.ceil(xLabels.size() / 10.0)); }
        public boolean isEmpty() { return series.isEmpty(); }
    }
    public static class Series {
        public final String name, cls;
        public String points;
        public final List<Dot> dots = new ArrayList<>();
        Series(String name, String cls) { this.name = name; this.cls = cls; }
    }
    public static class Dot {
        public final int x, y; public final String tip;
        Dot(int x, int y, String tip) { this.x = x; this.y = y; this.tip = tip; }
    }

    public static final int ROLLING_DAYS = 20;

    /** +12,300원 / −30,000원 */
    static String won(long v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format("%,d원", Math.abs(v)); }
    static String won0(long v) { return String.format("%,d원", v); }

    /** date 가 없거나 결과가 없는 날이면 결과가 있는 가장 최근 날 */
    public Report report(Stats s, String date) { return report(s, date, 5); }

    public Report report(Stats s, String date, int box) {
        Report rp = new Report();
        rp.boxK = box >= 3 && box <= 5 ? box : 5;
        rp.day.boxK = rp.boxK; rp.rolling.boxK = rp.boxK;
        TreeMap<String, List<RaceRec>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (Tally t : s.days) for (RaceRec r : t.recs) byDate.computeIfAbsent(r.date, k -> new ArrayList<>()).add(r);
        if (byDate.isEmpty()) return rp;
        rp.dates.addAll(byDate.keySet());
        rp.date = date != null && byDate.containsKey(date) ? date : byDate.firstKey();
        // 전체 기간 (영천 제외): 시장 비교와 확률 보정표
        for (String l : CALIB_LABELS) rp.calib.add(new CalibRow(l));
        for (List<RaceRec> l : byDate.values())
            for (RaceRec r : l) {
                if (!r.official) continue;
                rp.versus.add(r);
                for (double[] x : r.probs) {
                    int i = 0;
                    while (i < CALIB_LABELS.length - 1 && x[0] >= CALIB_EDGES[i + 1]) i++;
                    CalibRow c = rp.calib.get(i);
                    c.n++; c.pSum += x[0]; if (x[1] > 0) c.wins++;
                    rp.calibHorses++;
                }
            }
        List<RaceRec> sel = byDate.get(rp.date);
        sel.sort(Comparator.comparingInt((RaceRec r) -> r.meet == REFERENCE_MEET ? 9 : r.meet).thenComparingInt(r -> r.rcNo));
        rp.races.addAll(sel);
        for (RaceRec r : sel) if (r.official) rp.day.add(r);
        List<String> window = new ArrayList<>();
        for (String d : byDate.keySet()) if (d.compareTo(rp.date) <= 0 && window.size() < ROLLING_DAYS) window.add(d);
        rp.rollingDays = window.size();
        java.util.Collections.reverse(window);
        for (String d : window) {
            Agg a = new Agg();
            for (RaceRec r : byDate.get(d)) if (r.official) { a.add(r); rp.rolling.add(r); }
            if (a.n == 0) continue;
            rp.trendDates.add(d); rp.trend.add(a);
        }
        return rp;
    }

    public static class Stats {
        public final List<Tally> meets = new ArrayList<>(), days = new ArrayList<>();
        public boolean isEmpty() { return days.isEmpty(); }
        public Tally getTotal() { for (Tally t : meets) if (t.total) return t; return null; }
    }

    public static class Tally {
        public final String name;
        public String date;
        public int meet;
        public boolean total;
        public int races, pre1, preTop5, top3In5, favRaces, favWin, finN, finWin, dayRaces, day1;
        public double finRet;
        /** 경주별 기록 (검증 리포트용). 경주일 Tally 에만 채운다 */
        public final List<RaceRec> recs = new ArrayList<>();
        public final int[] poolN = new int[POOLS.length], poolHit = new int[POOLS.length];
        public final double[] poolRet = new double[POOLS.length];

        Tally(String name) { this.name = name; }

        void add(Tally o) {
            races += o.races; pre1 += o.pre1; preTop5 += o.preTop5; top3In5 += o.top3In5;
            favRaces += o.favRaces; favWin += o.favWin;
            dayRaces += o.dayRaces; day1 += o.day1;
            finN += o.finN; finWin += o.finWin; finRet += o.finRet;
            for (int i = 0; i < POOLS.length; i++) { poolN[i] += o.poolN[i]; poolHit[i] += o.poolHit[i]; poolRet[i] += o.poolRet[i]; }
        }

        public String getDateLabel() { return date == null ? "" : date.substring(4, 6) + "." + date.substring(6); }
        public String getPre1Rate() { return pct(pre1, races); }
        public String getPreTop5Rate() { return pct(preTop5, races); }
        public String getTop3In5Avg() { return races == 0 ? "-" : String.format("%.2f", (double) top3In5 / races); }
        public String getFavRate() { return pct(favWin, favRaces); }
        public String getDay1Rate() { return pct(day1, dayRaces); }
        /** 막대 길이용 (0~100) */
        public double getPre1Pct() { return races == 0 ? 0 : 100.0 * pre1 / races; }
        public double getDay1Pct() { return dayRaces == 0 ? 0 : 100.0 * day1 / dayRaces; }
        public double getFavPct() { return favRaces == 0 ? 0 : 100.0 * favWin / favRaces; }
        public String getFinRoi() { return finN == 0 ? "-" : String.format("%.0f%%", finRet / finN * 100); }
        public List<PoolRow> getPools() {
            List<PoolRow> l = new ArrayList<>();
            for (int i = 0; i < POOLS.length; i++) l.add(new PoolRow(i, poolN[i], poolHit[i], poolRet[i]));
            return l;
        }
        /** 승식 표에서 단승 외 배당이 하나라도 있나 */
        public boolean isHasPoolDiv() { return poolN[1] + poolN[2] + poolN[3] + poolN[4] > 0; }
        public boolean isFinUp() { return finN > 0 && finRet >= finN; }

        private static String pct(int a, int b) { return b == 0 ? "-" : String.format("%.1f%%", 100.0 * a / b); }
    }
}
