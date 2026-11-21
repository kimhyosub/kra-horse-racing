package com.sub.gachima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.gachima.domain.*;
import com.sub.gachima.domain.Repositories.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * kra-raceday/export_web.py 가 만든 day-YYYYMMDD.json 을 DB로 불러온다.
 * 같은 날짜를 다시 불러오면 전날 예상·착순·최종 배당만 갱신하고, 직접 입력한 당일 배당은 그대로 둔다.
 * 폴더 훑기는 ImportScanner 가 한다.
 */
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final MeetDayRepository meetDays;
    private final RaceRepository races;
    private final DayInputRepository inputs;
    private final PaperService paper;
    private final ObjectMapper om;
    private final File dir;

    public ImportService(MeetDayRepository meetDays, RaceRepository races, DayInputRepository inputs, PaperService paper,
                         ObjectMapper om, @Value("${gachima.import-dir}") String dir) {
        this.meetDays = meetDays; this.races = races; this.inputs = inputs; this.paper = paper; this.om = om; this.dir = new File(dir);
    }

    public File getDir() { return dir; }

    @Transactional
    public void importFile(File file) throws IOException {
        JsonNode doc = om.readTree(file);
        String date = doc.path("date").asText();
        String feats = doc.path("feats").toString(), bin = doc.path("bin").toString(), dayOnly = doc.path("dayOnly").toString();
        int nRace = 0, nHorse = 0;
        for (JsonNode m : doc.path("meets")) {
            int meet = m.path("meet").asInt();
            MeetDay md = meetDays.findByRaceDateAndMeet(date, meet).orElseGet(MeetDay::new);
            md.setRaceDate(date); md.setMeet(meet); md.setMeetName(m.path("name").asText());
            md.setFeatsJson(feats); md.setBinJson(bin); md.setDayOnlyJson(dayOnly);
            md.setDayWeightsJson(m.path("dayWeights").toString());
            if (doc.path("pre").isObject()) md.setPreWeightsJson(doc.path("pre").toString());
            md = meetDays.save(md);
            for (JsonNode r : m.path("races")) {
                int rc = r.path("rcNo").asInt();
                Race race = races.findByMeetDayAndRcNo(md, rc).orElseGet(Race::new);
                race.setMeetDay(md); race.setRcNo(rc);
                race.setStTime(r.path("stTime").asText()); race.setDist(r.path("dist").asInt());
                Map<Integer, Entry> byNo = new HashMap<>();
                for (Entry e : race.getEntries()) byNo.put(e.getChulNo(), e);
                for (JsonNode h : r.path("horses")) {
                    int no = h.path("no").asInt();
                    Entry e = byNo.get(no);
                    if (e == null) { e = new Entry(); e.setRace(race); e.setChulNo(no); race.getEntries().add(e); }
                    e.setHrNo(h.path("hrNo").asText(null)); e.setHrName(h.path("name").asText());
                    e.setJkName(text(h, "jkName")); e.setTrName(text(h, "trName"));
                    e.setDays(h.path("days").isNumber() ? h.path("days").asDouble() : null);
                    e.setFirstStart(h.path("first").asInt() == 1);
                    e.setFeaturesJson(h.path("f").toString());
                    e.setFactsJson(h.path("facts").isObject() ? h.path("facts").toString() : null);
                    e.setPreWhyJson(h.path("preWhy").isObject() ? h.path("preWhy").toString() : null);
                    e.setPreP(h.path("preP").asDouble()); e.setPreRank(h.path("preRank").asInt());
                    e.setFinishOrd(h.path("ord").isNumber() ? h.path("ord").asInt() : null);
                    e.setFinalOdds(h.path("finalOdds").isNumber() ? h.path("finalOdds").asDouble() : null);
                    nHorse++;
                }
                race = races.save(race);
                saveFinal(race, r.path("final"));
                paper.settle(race);
                nRace++;
            }
        }
        log.info("불러옴 {}: {}경주 {}두", file.getName(), nRace, nHorse);
    }

    /** 경주가 끝난 뒤의 최종 배당·마체중·주로를 'final' 입력 하나로 보관 (다시 불러오면 덮어쓴다) */
    private void saveFinal(Race race, JsonNode fin) {
        if (!fin.isObject() || fin.path("odds").size() < 2) return;
        DayInput in = inputs.findFirstByRaceAndSourceOrderByCreatedAtDescIdDesc(race, RaceService.SRC_FINAL).orElseGet(DayInput::new);
        String track = text(fin, "track");
        in.setRace(race);
        in.setTrack(track != null && RaceService.TRACKS.contains(track) ? track : "건조");
        in.setOddsJson(fin.path("odds").toString());
        in.setBwdJson(fin.path("bwd").isObject() ? fin.path("bwd").toString() : "{}");
        in.setSource(RaceService.SRC_FINAL);
        in.setCreatedAt(LocalDateTime.now());
        inputs.save(in);
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
