package com.sub.gachima.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.gachima.domain.*;
import com.sub.gachima.domain.Repositories.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 경주 화면에 필요한 값을 모으고, 당일 입력을 저장한다. */
@Service
public class RaceService {

    public static final List<String> TRACKS = Arrays.asList("건조", "양호", "다습", "포화", "불량");
    public static final String SRC_MANUAL = "manual", SRC_FINAL = "final";

    private final MeetDayRepository meetDays;
    private final RaceRepository races;
    private final DayInputRepository inputs;
    private final ObjectMapper om;
    private final PaperService paper;

    private org.springframework.context.ApplicationEventPublisher events = e -> { };

    public RaceService(MeetDayRepository meetDays, RaceRepository races, DayInputRepository inputs, ObjectMapper om, PaperService paper) {
        this.meetDays = meetDays; this.races = races; this.inputs = inputs; this.om = om; this.paper = paper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(org.springframework.context.ApplicationEventPublisher events) { if (events != null) this.events = events; }

    public Optional<MeetDay> latest() {
        return meetDays.findAllByOrderByRaceDateDescMeetAsc().stream().findFirst();
    }

    public List<MeetDay> meetsOn(String date) { return meetDays.findByRaceDateOrderByMeet(date); }

    public List<MeetDay> allMeetDays() { return meetDays.findAllByOrderByRaceDateDescMeetAsc(); }

    // ---------------- 화면 ----------------

    @Transactional(readOnly = true)
    public Optional<RaceView> view(String date, int meet, int rcNo) {
        Optional<MeetDay> md = meetDays.findByRaceDateAndMeet(date, meet);
        if (!md.isPresent()) return Optional.empty();
        List<Race> all = races.findByMeetDayOrderByRcNo(md.get());
        Optional<Race> race = all.stream().filter(r -> r.getRcNo() == rcNo).findFirst();
        if (!race.isPresent()) return Optional.empty();

        DayModel model = model(md.get());
        RaceView v = new RaceView();
        v.date = date; v.meet = meet; v.meetName = md.get().getMeetName();
        v.meets = meetsOn(date).stream().map(m -> new MeetTab(m.getMeet(), m.getMeetName())).collect(Collectors.toList());
        v.days = dayTabs(meet);
        for (int i = 0; i < v.days.size(); i++)
            if (v.days.get(i).date.equals(date)) {   // 목록은 최근 날짜가 앞
                if (i + 1 < v.days.size()) v.prevDay = v.days.get(i + 1);
                if (i > 0) v.nextDay = v.days.get(i - 1);
            }
        for (Race r : all) v.tabs.add(new RaceTab(r.getRcNo(), r.getStTime(), hasValue(model, r)));
        fill(v, race.get(), model);
        return Optional.of(v);
    }

    /** 그날의 모든 경주 (텔레그램 스크린샷이 어느 경주인지 찾을 때) */
    @Transactional(readOnly = true)
    public List<RaceRef> racesOn(String date) {
        List<RaceRef> out = new ArrayList<>();
        for (MeetDay md : meetDays.findByRaceDateOrderByMeet(date))
            for (Race r : races.findByMeetDayOrderByRcNo(md)) {
                RaceRef x = new RaceRef();
                x.date = date; x.meet = md.getMeet(); x.meetName = md.getMeetName(); x.rcNo = r.getRcNo(); x.stTime = r.getStTime();
                for (Entry e : r.getEntries()) x.names.put(e.getChulNo(), e.getHrName());
                out.add(x);
            }
        return out;
    }

    /** 그날 그 경마장에서 마지막으로 직접 입력한 주로 상태 */
    @Transactional(readOnly = true)
    public Optional<String> lastManualTrack(String date, int meet) {
        DayInput best = null;
        for (MeetDay md : meetDays.findByRaceDateOrderByMeet(date)) {
            if (md.getMeet() != meet) continue;
            for (Race r : races.findByMeetDayOrderByRcNo(md)) {
                Optional<DayInput> in = input(r, SRC_MANUAL);
                if (in.isPresent() && (best == null || in.get().getCreatedAt().isAfter(best.getCreatedAt()))) best = in.get();
            }
        }
        return Optional.ofNullable(best == null ? null : best.getTrack());
    }

    private Map<String, Object> readObj(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try { return om.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (IOException e) { return Collections.emptyMap(); }
    }

    private Map<String, Double> readDoubles(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try { return om.readValue(json, new TypeReference<Map<String, Double>>() {}); }
        catch (IOException e) { return Collections.emptyMap(); }
    }

    public static class RaceRef {
        public String date, meetName, stTime;
        public int meet, rcNo;
        public final Map<Integer, String> names = new TreeMap<>();
    }

    /** 직접 입력이 있으면 그것, 없으면 경주 후 들어온 최종 배당 */
    Optional<DayInput> currentInput(Race r) {
        Optional<DayInput> m = input(r, SRC_MANUAL);
        return m.isPresent() ? m : input(r, SRC_FINAL);
    }

    Optional<DayInput> input(Race r, String source) {
        return inputs.findFirstByRaceAndSourceOrderByCreatedAtDescIdDesc(r, source);
    }

    private boolean hasValue(DayModel model, Race r) {
        Optional<DayInput> in = currentInput(r);
        if (!in.isPresent()) return false;
        return model.score(horses(r, in.get()), in.get().getTrack()).stream().anyMatch(x -> x.value);
    }

    private void fill(RaceView v, Race race, DayModel model) {
        v.rcNo = race.getRcNo(); v.stTime = race.getStTime(); v.dist = race.getDist();
        Optional<DayInput> in = currentInput(race);
        v.inputSource = in.map(DayInput::getSource).orElse(null);
        v.inputBy = in.map(DayInput::getCreatedBy).orElse(null);
        v.track = in.map(DayInput::getTrack).orElse("건조");
        v.inputAt = in.map(DayInput::getCreatedAt).orElse(null);
        Map<Integer, Double> odds = in.map(i -> readMap(i.getOddsJson())).orElse(Collections.emptyMap());
        Map<Integer, Double> bwd = in.map(i -> readMap(i.getBwdJson())).orElse(Collections.emptyMap());

        Map<Integer, DayModel.Result> day = new HashMap<>();
        if (in.isPresent()) for (DayModel.Result r : model.score(horses(race, in.get()), v.track)) day.put(r.no, r);
        List<DayModel.Result> byP = new ArrayList<>(day.values());
        byP.sort((a, b) -> Double.compare(b.pModel, a.pModel));
        Map<Integer, Integer> dayRank = new HashMap<>();
        for (int i = 0; i < byP.size(); i++) dayRank.put(byP.get(i).no, i + 1);
        double maxAbs = 0.15;
        for (DayModel.Result r : byP) for (double g : r.groups.values()) maxAbs = Math.max(maxAbs, Math.abs(g));
        double scale = 0;
        for (DayModel.Result r : byP) scale = Math.max(scale, Math.max(r.pModel, r.pMarket));
        scale *= 1.08;
        double preMax = race.getEntries().stream().mapToDouble(Entry::getPreP).max().orElse(1) * 1.08;

        for (Entry e : race.getEntries()) {
            EntryView x = new EntryView();
            x.no = e.getChulNo(); x.name = e.getHrName(); x.jk = e.getJkName(); x.tr = e.getTrName();
            x.preP = e.getPreP(); x.preRank = e.getPreRank(); x.preBar = e.getPreP() / preMax * 100;
            x.ord = e.getFinishOrd(); x.finalOdds = e.getFinalOdds();
            x.inputOdds = odds.get(e.getChulNo()); x.inputBwd = bwd.get(e.getChulNo());
            x.firstStart = e.isFirstStart();
            Map<String, Object> run = readObj(e.getRunJson());
            x.s1 = intOf(run.get("s1")); x.c4 = intOf(run.get("c4"));
            x.g1 = dblOf(run.get("g1")); x.gap = dblOf(run.get("gap"));
            Map<String, Object> facts = readObj(e.getFactsJson());
            x.facts = facts;
            x.preWhy = readDoubles(e.getPreWhyJson());
            x.record = PreReasons.record(facts);
            x.reasons = PreReasons.of(x.preWhy, facts, 3);
            DayModel.Result r = day.get(e.getChulNo());
            if (r != null) {
                x.scored = true; x.odds = r.odds; x.pm = r.pMarket; x.p = r.pModel; x.ev = r.ev; x.value = r.value;
                x.dayRank = dayRank.get(x.no); x.move = x.preRank - x.dayRank;
                x.xm = r.pMarket / scale * 100; x.xp = r.pModel / scale * 100;
                for (Map.Entry<String, Double> g : r.groups.entrySet()) x.groups.add(new GroupBar(g.getKey(), g.getValue(), maxAbs));
                x.why = why(x, r);
                x.dayReasons = PreReasons.day(r.groups, facts, x.inputBwd, 3);
            }
            v.entries.add(x);
        }
        v.preOrder = v.entries.stream().sorted(Comparator.comparingInt(a -> a.preRank)).collect(Collectors.toList());
        v.dayOrder = v.entries.stream().filter(a -> a.scored).sorted((a, b) -> Double.compare(b.p, a.p)).collect(Collectors.toList());
        v.valueOrder = v.entries.stream().filter(a -> a.scored).sorted((a, b) -> Double.compare(b.ev, a.ev)).collect(Collectors.toList());
        v.result = v.entries.stream().filter(a -> a.ord != null && a.ord <= 3).sorted(Comparator.comparingInt(a -> a.ord))
                .map(a -> a.no).collect(Collectors.toList());
        v.values = v.dayOrder.stream().filter(a -> a.value).collect(Collectors.toList());
        v.paperBets = paper.forRace(race);
        v.paperLock = paper.lockState(race);
        v.review = Commentary.of(v.entries, v.track, true);
        v.picks = BetPicks.of(v.dayOrder);
        v.preReview = Commentary.of(v.entries, null, false);
        // 마지막 200m 빠르기 순위 (같은 경주 안)
        List<EntryView> byG1 = v.entries.stream().filter(a -> a.g1 != null).sorted(Comparator.comparingDouble(a -> a.g1)).collect(Collectors.toList());
        for (int i = 0; i < byG1.size(); i++) byG1.get(i).g1Rank = i + 1;
        v.recap = RaceRecap.of(v);
    }

    private static Integer intOf(Object o) { return o instanceof Number ? ((Number) o).intValue() : null; }
    private static Double dblOf(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : null; }

    private static String why(EntryView x, DayModel.Result r) {
        List<Map.Entry<String, Double>> g = new ArrayList<>(r.groups.entrySet());
        g.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> up = g.stream().filter(e -> e.getValue() > 0.02).limit(2).map(Map.Entry::getKey).collect(Collectors.toList());
        List<String> dn = new ArrayList<>();
        for (int i = g.size() - 1; i >= 0 && dn.size() < 2; i--) if (g.get(i).getValue() < -0.02) dn.add(g.get(i).getKey());
        StringBuilder s = new StringBuilder(String.format("시장 %.1f%% → 모델 %.1f%%.", r.pMarket * 100, r.pModel * 100));
        if (!up.isEmpty()) s.append(" 더한 요인: ").append(String.join(", ", up)).append('.');
        if (!dn.isEmpty()) s.append(" 뺀 요인: ").append(String.join(", ", dn)).append('.');
        s.append(String.format(" (전날 예상 %d위, %.1f%%)", x.preRank, x.preP * 100));
        return s.toString();
    }

    // ---------------- 당일 입력 ----------------

    /** 입력 저장 + 출발 전이면 모의 기록(★)을 이번 입력으로 바꿔 쓴다 */
    @Transactional
    public PaperService.Outcome saveInput(String date, int meet, int rcNo, String track, Map<Integer, String> odds, Map<Integer, String> bwd, String user) {
        MeetDay md = meetDays.findByRaceDateAndMeet(date, meet).orElseThrow(() -> new NoSuchElementException("경주일 없음"));
        Race race = races.findByMeetDayAndRcNo(md, rcNo).orElseThrow(() -> new NoSuchElementException("경주 없음"));
        DayInput in = new DayInput();
        in.setRace(race);
        in.setTrack(TRACKS.contains(track) ? track : "건조");
        in.setOddsJson(write(parse(odds, true)));
        in.setBwdJson(write(parse(bwd, false)));
        in.setSource(SRC_MANUAL);
        in.setCreatedBy(user);
        in.setCreatedAt(LocalDateTime.now());
        inputs.save(in);
        List<DayModel.Result> scored = model(md).score(horses(race, in), in.getTrack());
        PaperService.Outcome o = paper.record(race, scored, in.getTrack(), readMap(in.getBwdJson()), user);
        if (o == PaperService.Outcome.RECORDED) events.publishEvent(new RaceEvent(RaceEvent.Kind.INPUT, date, meet, rcNo));
        return o;
    }

    /**
     * 텔레그램 등으로 직접 넣는 결과(착순). 적은 말은 그 착순, 취소로 적은 말과 당일 배당이 없던 말은 취소,
     * 나머지는 착외(ORD_OUT). 확정 단승 배당을 알면 함께 넣는다. 저장 뒤 모의 기록을 정산한다.
     * 공식 결과가 수집되면 ImportService 가 덮어쓴다.
     */
    @Transactional
    public void saveResult(String date, int meet, int rcNo, Map<Integer, Integer> ords, Set<Integer> scratched, Map<Integer, Double> finalOdds) {
        MeetDay md = meetDays.findByRaceDateAndMeet(date, meet).orElseThrow(() -> new NoSuchElementException("경주일 없음"));
        Race race = races.findByMeetDayAndRcNo(md, rcNo).orElseThrow(() -> new NoSuchElementException("경주 없음"));
        Set<Integer> nos = new TreeSet<>();
        for (Entry e : race.getEntries()) nos.add(e.getChulNo());
        List<Integer> bad = new ArrayList<>();
        for (Integer no : ords.keySet()) if (!nos.contains(no)) bad.add(no);
        if (!bad.isEmpty()) throw new IllegalArgumentException(bad + "번은 이 경주 출전마가 아닙니다");
        if (ords.isEmpty()) throw new IllegalArgumentException("착순이 없습니다");
        Map<Integer, Double> dayOdds = input(race, SRC_MANUAL).map(i -> readMap(i.getOddsJson())).orElse(Collections.<Integer, Double>emptyMap());
        for (Entry e : race.getEntries()) {
            int no = e.getChulNo();
            if (ords.containsKey(no)) e.setFinishOrd(ords.get(no));
            else if (scratched.contains(no) || (!dayOdds.isEmpty() && !dayOdds.containsKey(no))) e.setFinishOrd(null);
            else e.setFinishOrd(Entry.ORD_OUT);
            Double fo = finalOdds.get(no);
            if (fo != null && fo >= 1.0) e.setFinalOdds(fo);
        }
        races.save(race);
        paper.settle(race);
        events.publishEvent(new RaceEvent(RaceEvent.Kind.RESULT, date, meet, rcNo));
    }

    /** "12.5", "+3", "-4" 같은 입력을 숫자로. 배당은 1.0 이상만 받는다. */
    static Map<Integer, Double> parse(Map<Integer, String> raw, boolean odds) {
        Map<Integer, Double> out = new TreeMap<>();
        for (Map.Entry<Integer, String> e : raw.entrySet()) {
            String s = e.getValue() == null ? "" : e.getValue().trim().replace("+", "").replace("배", "");
            if (s.isEmpty() || s.equals("-")) continue;
            try {
                double d = Double.parseDouble(s);
                if (odds && d < 1.0) continue;
                out.put(e.getKey(), d);
            } catch (NumberFormatException ignore) { /* 숫자가 아닌 칸은 비운 것으로 본다 */ }
        }
        return out;
    }

    // ---------------- 내부 ----------------

    DayModel model(MeetDay md) {
        try {
            List<String> feats = om.readValue(md.getFeatsJson(), new TypeReference<List<String>>() {});
            List<String> bin = om.readValue(md.getBinJson(), new TypeReference<List<String>>() {});
            double[] w = om.readValue(md.getDayWeightsJson(), double[].class);
            return new DayModel(feats, bin, w);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    List<DayModel.Horse> horses(Race race, DayInput in) {
        Map<Integer, Double> odds = readMap(in.getOddsJson()), bwd = readMap(in.getBwdJson());
        List<DayModel.Horse> out = new ArrayList<>();
        for (Entry e : race.getEntries()) {
            Double o = odds.get(e.getChulNo());
            if (o == null) continue;   // 배당 없음 = 출전 취소
            out.add(new DayModel.Horse(e.getChulNo(), readArray(e.getFeaturesJson()), e.getDays(), o, bwd.get(e.getChulNo())));
        }
        return out;
    }

    private Map<Integer, Double> readMap(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try { return om.readValue(json, new TypeReference<Map<Integer, Double>>() {}); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private Double[] readArray(String json) {
        try { return om.readValue(json, Double[].class); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private String write(Object o) {
        try { return om.writeValueAsString(o); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // ---------------- 화면용 값 ----------------

    public static class RaceView {
        public String date, meetName, stTime, track, inputSource, inputBy;
        public int meet, rcNo, dist;
        public LocalDateTime inputAt;
        public List<MeetTab> meets = new ArrayList<>();
        public List<DayTab> days = new ArrayList<>();
        public DayTab prevDay, nextDay;
        public List<RaceTab> tabs = new ArrayList<>();
        public List<EntryView> entries = new ArrayList<>(), preOrder, dayOrder, valueOrder, values;
        public List<Integer> result;
        public List<com.sub.gachima.domain.PaperBet> paperBets = new ArrayList<>();
        public PaperService.Outcome paperLock;
        /** 총평: 당일 분석 기준 / 전날 예상 기준 */
        public Commentary review, preReview;
        /** 경주 복기: 결과 뒤 구간 기록으로 '어떻게 달렸나' */
        public RaceRecap recap;
        public BetPicks picks;
        public boolean isPaperLocked() { return paperLock != null && paperLock != PaperService.Outcome.RECORDED; }
        public String getDateLabel() { return date.substring(0, 4) + "." + date.substring(4, 6) + "." + date.substring(6); }
        /** 검색·공유용 설명: 날짜·경주·거리·출발 + 전날 예상 1~3위 */
        public String getSeoTitle() {
            return getDayLabelShort() + " " + meetName + " " + rcNo + "경주 예상·분석 | 가치마";
        }
        public String getSeoDesc() {
            StringBuilder b = new StringBuilder();
            b.append(getDayLabelShort()).append(' ').append(meetName).append(' ').append(rcNo).append("경주 ")
             .append(dist).append("m");
            if (stTime != null && !stTime.isEmpty()) b.append(", ").append(stTime).append(" 출발");
            b.append(". 전날 예상 ");
            List<EntryView> t = preOrder.subList(0, Math.min(3, preOrder.size()));
            for (int i = 0; i < t.size(); i++) { if (i > 0) b.append(", "); b.append(i + 1).append("위 ").append(t.get(i).no).append("번 ").append(t.get(i).name); }
            b.append(". 당일 배당으로 다시 계산한 가치마(★)와 요인별 점수표, 공개 성적을 함께 봅니다.");
            return b.toString();
        }
        private String getDayLabelShort() {
            java.time.LocalDate d = java.time.LocalDate.parse(date, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일(" + "월화수목금토일".charAt(d.getDayOfWeek().getValue() - 1) + ")";
        }
        public List<EntryView> getPreTop5() { return preOrder.subList(0, Math.min(5, preOrder.size())); }
        public List<EntryView> getDayTop3() { return dayOrder.subList(0, Math.min(3, dayOrder.size())); }
        /** 가치마가 없을 때 기대값이 가장 높은 말 */
        public EntryView getBestEv() { return valueOrder == null || valueOrder.isEmpty() ? null : valueOrder.get(0); }
        public boolean isScored() { return !dayOrder.isEmpty(); }
        public String getInputByLabel() { return inputBy == null ? "" : " · " + inputBy; }
        public boolean isFinalInput() { return SRC_FINAL.equals(inputSource); }
    }

    /** 경주일 목록 (최근이 앞). 그날 같은 경마장이 있으면 그 경마장으로, 없으면 첫 경마장으로 연결 */
    List<DayTab> dayTabs(int preferMeet) {
        Map<String, List<MeetDay>> byDate = new LinkedHashMap<>();
        for (MeetDay md : meetDays.findAllByOrderByRaceDateDescMeetAsc()) byDate.computeIfAbsent(md.getRaceDate(), k -> new ArrayList<>()).add(md);
        List<DayTab> out = new ArrayList<>();
        for (Map.Entry<String, List<MeetDay>> e : byDate.entrySet()) {
            int meet = e.getValue().get(0).getMeet();
            for (MeetDay md : e.getValue()) if (md.getMeet() == preferMeet) meet = preferMeet;
            String names = e.getValue().stream().map(MeetDay::getMeetName).collect(Collectors.joining("·"));
            out.add(new DayTab(e.getKey(), meet, names));
        }
        return out;
    }

    public static class DayTab {
        public final String date, label; public final int meet;
        DayTab(String date, int meet, String meetNames) {
            this.date = date; this.meet = meet;
            String dow = "";
            try {
                dow = " (" + "월화수목금토일".charAt(java.time.LocalDate.parse(date, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).getDayOfWeek().getValue() - 1) + ")";
            } catch (RuntimeException ignore) { }
            this.label = date.substring(4, 6) + "." + date.substring(6) + dow;   // 경마장은 옆 버튼으로 고름
        }
    }

    public static class MeetTab {
        public final int meet; public final String name;
        MeetTab(int meet, String name) { this.meet = meet; this.name = name; }
    }

    public static class RaceTab {
        public final int rcNo; public final String stTime; public final boolean value;
        RaceTab(int rcNo, String stTime, boolean value) { this.rcNo = rcNo; this.stTime = stTime; this.value = value; }
    }

    public static class EntryView {
        public int no, preRank;
        public Integer ord, dayRank, move;
        public String name, jk, tr, why;
        /** 전날 예상 근거: 기록 한 줄, 순위를 움직인 요인 */
        public String record = "";
        public Map<String, Object> facts = Collections.emptyMap();
        public Map<String, Double> preWhy = Collections.emptyMap();
        public List<PreReasons.Reason> reasons = new ArrayList<>(), dayReasons = new ArrayList<>();
        public double preP, preBar, odds, pm, p, ev, xm, xp;
        public Double finalOdds, inputOdds, inputBwd;
        public boolean scored, value, firstStart;
        /** 경주 복기: 초반(S1F) 순위, 4코너 순위, 마지막 200m 초와 그 빠르기 순위, 1착과 기록 차 */
        public Integer s1, c4, g1Rank;
        public Double g1, gap;
        public List<GroupBar> groups = new ArrayList<>();
        public String getOrdLabel() { return ord == null ? "" : ord >= Entry.ORD_OUT ? "착외" : ord + "착"; }
        public double getGapLeft() { return Math.min(xm, xp); }
        public double getGapWidth() { return Math.abs(xp - xm); }
        public boolean isUp() { return xp > xm; }
        public String getGapStyle() { return isUp() ? "background:var(--value);opacity:.55" : "background:var(--line);opacity:1"; }
        /** 카드 아랫줄: 기수 · 배당 · 마체중 */
        public String getCardSub() {
            StringBuilder b = new StringBuilder(jk == null ? "-" : jk);
            b.append(" · ").append(fmtOdds(odds)).append("배");
            if (inputBwd != null) b.append(" · 마체중 ").append(inputBwd > 0 ? "+" : "").append(Math.round(inputBwd)).append("kg");
            return b.toString();
        }
        public String getMoveLabel() {
            if (move == null) return "";
            return "전날 " + preRank + "위" + (move > 0 ? " ▲" + move : move < 0 ? " ▼" + (-move) : " –");
        }
        public String getEvFormula() {
            return String.format("%.1f%% × %s배", p * 100, fmtOdds(odds));
        }
        static String fmtOdds(double o) { return o == Math.rint(o) ? String.format("%.1f", o) : String.valueOf(o); }
        public String getDaySub() {
            StringBuilder b = new StringBuilder();
            b.append(jk == null ? "-" : jk).append(" · ").append(odds).append("배");
            if (inputBwd != null) b.append(" · ").append(inputBwd > 0 ? "+" : "").append(Math.round(inputBwd)).append("kg");
            return b.toString();
        }
    }

    public static class GroupBar {
        public final String name; public final double value, left, width;
        GroupBar(String name, double value, double maxAbs) {
            this.name = name; this.value = value;
            this.width = Math.abs(value) / maxAbs * 50;
            this.left = value >= 0 ? 50 : 50 - width;
        }
        public boolean isPositive() { return value >= 0; }
        public String getColor() { return value >= 0 ? "--model" : "--mkt"; }
        public String getLabel() { return (value >= 0 ? "+" : "") + String.format("%.2f", value); }
    }
}
