package com.sub.gachima;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.gachima.service.DayModel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Python(kra_features.score)과 같은 값이 나오는지 확인.
 * 기준: 2026-11-15 서울 7R, 14:47 화면 배당·마체중, 다습. 같은 요인값(day-20261115.json)으로 kra_features.score() 를 돌린 값.
 * (경주일 기록값과는 2번의 기수 변경 반영 여부만 다르다)
 */
class DayModelTest {

    // 마번: {배당, 마체중 증감, Python 모델확률, Python 시장확률}
    static final double[][] REF = {
            {1, 8.7, 0, 0.10476, 0.09207}, {2, 13.8, -1, 0.06034, 0.05804}, {3, 3.9, 0, 0.21844, 0.20539},
            {4, 10, 1, 0.07276, 0.0801}, {5, 7, 6, 0.10478, 0.11443}, {6, 13.1, -1, 0.05924, 0.06115},
            {7, 15.6, 4, 0.06158, 0.05135}, {8, 15.4, -7, 0.08516, 0.05201}, {9, 20.5, 6, 0.02721, 0.03907},
            {10, 4.1, 2, 0.15888, 0.19537}, {11, 15.7, 8, 0.04685, 0.05102}};

    @Test
    void matchesPythonScore() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode doc;
        try (InputStream in = getClass().getResourceAsStream("/day-20261115.json")) { doc = om.readTree(in); }
        List<String> feats = new ArrayList<>();
        doc.path("feats").forEach(n -> feats.add(n.asText()));
        List<String> bin = new ArrayList<>();
        doc.path("bin").forEach(n -> bin.add(n.asText()));
        JsonNode seoul = doc.path("meets").get(0);
        double[] w = om.convertValue(seoul.path("dayWeights"), double[].class);
        JsonNode race = null;
        for (JsonNode r : seoul.path("races")) if (r.path("rcNo").asInt() == 7) race = r;
        assertNotNull(race);

        Map<Integer, JsonNode> byNo = new HashMap<>();
        race.path("horses").forEach(h -> byNo.put(h.path("no").asInt(), h));
        List<DayModel.Horse> hs = new ArrayList<>();
        for (double[] ref : REF) {
            JsonNode h = byNo.get((int) ref[0]);
            Double[] f = om.convertValue(h.path("f"), Double[].class);
            Double days = h.path("days").isNumber() ? h.path("days").asDouble() : null;
            hs.add(new DayModel.Horse((int) ref[0], f, days, ref[1], ref[2]));
        }

        List<DayModel.Result> out = new DayModel(feats, bin, w).score(hs, "다습");
        assertEquals(11, out.size());
        Map<Integer, DayModel.Result> got = new HashMap<>();
        for (DayModel.Result r : out) got.put(r.no, r);
        for (double[] ref : REF) {
            DayModel.Result r = got.get((int) ref[0]);
            assertEquals(ref[4], r.pMarket, 1e-4, "시장확률 " + (int) ref[0] + "번");
            assertEquals(ref[3], r.pModel, 1e-4, "모델확률 " + (int) ref[0] + "번");
        }
        assertTrue(got.get(8).value, "8번 마스터플랜은 가치마(기대값 1.31)");
        assertEquals(1.3115, got.get(8).ev, 1e-3);
        long values = out.stream().filter(r -> r.value).count();
        assertEquals(1, values);
    }

    @Test
    void needsTwoHorsesWithOdds() {
        DayModel m = new DayModel(Collections.singletonList("form3"), Collections.<String>emptyList(), new double[]{1.0, 0.1});
        List<DayModel.Horse> one = Collections.singletonList(new DayModel.Horse(1, new Double[]{0.5}, 10.0, 3.0, null));
        assertTrue(m.score(one, "건조").isEmpty());
    }
}
