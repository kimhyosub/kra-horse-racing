package com.sub.gachima.service;

import com.sub.gachima.domain.*;
import com.sub.gachima.domain.Repositories.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

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
        t.races++;
        if (win.getPreRank() == 1) t.pre1++;
        if (win.getPreRank() <= 5) t.preTop5++;
        for (Entry e : es) if (e.getFinishOrd() != null && e.getFinishOrd() <= 3 && e.getPreRank() <= 5) t.top3In5++;
        if (fav != null) { t.favRaces++; if (fav.getChulNo() == win.getChulNo()) t.favWin++; }

        final Entry w = win;
        // 당일 분석 1위 적중: 출발 전 직접 입력이 있으면 그것, 없으면 확정 배당으로 계산한 당일 1위
        Optional<DayInput> dayIn = raceService.input(r, RaceService.SRC_MANUAL);
        if (!dayIn.isPresent()) dayIn = raceService.input(r, RaceService.SRC_FINAL);
        dayIn.ifPresent(in -> {
            DayModel.Result top = null;
            for (DayModel.Result x : model.score(raceService.horses(r, in), in.getTrack()))
                if (top == null || x.pModel > top.pModel) top = x;
            if (top != null) { t.dayRaces++; if (top.no == w.getChulNo()) t.day1++; }
        });
        raceService.input(r, RaceService.SRC_FINAL).ifPresent(in -> {
            for (DayModel.Result x : model.score(raceService.horses(r, in), in.getTrack())) {
                if (!x.value) continue;
                t.finN++;
                if (x.no == w.getChulNo()) { t.finWin++; t.finRet += w.getFinalOdds() != null ? w.getFinalOdds() : x.odds; }
            }
        });
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

        Tally(String name) { this.name = name; }

        void add(Tally o) {
            races += o.races; pre1 += o.pre1; preTop5 += o.preTop5; top3In5 += o.top3In5;
            favRaces += o.favRaces; favWin += o.favWin;
            dayRaces += o.dayRaces; day1 += o.day1;
            finN += o.finN; finWin += o.finWin; finRet += o.finRet;
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
        public boolean isFinUp() { return finN > 0 && finRet >= finN; }

        private static String pct(int a, int b) { return b == 0 ? "-" : String.format("%.1f%%", 100.0 * a / b); }
    }
}
