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
import java.util.*;

/**
 * kra-raceday/export_web.py 가 만든 day-YYYYMMDD.json 을 DB로 불러온다.
 * 같은 날짜를 다시 불러오면 전날 예상·착순만 갱신하고, 당일 입력은 그대로 둔다.
 */
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final MeetDayRepository meetDays;
    private final RaceRepository races;
    private final ObjectMapper om;
    private final File dir;

    public ImportService(MeetDayRepository meetDays, RaceRepository races, ObjectMapper om,
                         @Value("${gachima.import-dir}") String dir) {
        this.meetDays = meetDays; this.races = races; this.om = om; this.dir = new File(dir);
    }

    public File getDir() { return dir; }

    /** 폴더의 day-*.json 전부 불러오기. 반환: 불러온 파일 이름 */
    @Transactional
    public List<String> importAll() throws IOException {
        List<String> done = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.startsWith("day-") && n.endsWith(".json"));
        if (files == null) return done;
        Arrays.sort(files);
        for (File f : files) { importFile(f); done.add(f.getName()); }
        return done;
    }

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
                    e.setPreP(h.path("preP").asDouble()); e.setPreRank(h.path("preRank").asInt());
                    e.setFinishOrd(h.path("ord").isNumber() ? h.path("ord").asInt() : null);
                    e.setFinalOdds(h.path("finalOdds").isNumber() ? h.path("finalOdds").asDouble() : null);
                    nHorse++;
                }
                races.save(race);
                nRace++;
            }
        }
        log.info("불러옴 {}: {}경주 {}두", file.getName(), nRace, nHorse);
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
