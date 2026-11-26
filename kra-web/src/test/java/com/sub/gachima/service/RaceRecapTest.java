package com.sub.gachima.service;

import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceView;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 경주 복기 문장: 우승마 경로, 가치마 1위가 진 이유, 다음에 볼 말 */
class RaceRecapTest {

    static EntryView h(int no, String name, int ord, Integer s1, Integer c4, Double g1, Double gap, double p) {
        EntryView e = new EntryView();
        e.no = no; e.name = name; e.ord = ord; e.s1 = s1; e.c4 = c4; e.g1 = g1; e.gap = gap;
        e.scored = true; e.p = p; e.preRank = no;
        return e;
    }

    static RaceView view(List<EntryView> es) {
        RaceView v = new RaceView();
        v.entries.addAll(es);
        v.dayOrder = es.stream().sorted((a, b) -> Double.compare(b.p, a.p)).collect(Collectors.toList());
        v.preOrder = new ArrayList<>(es);
        v.values = new ArrayList<>();
        List<EntryView> byG1 = es.stream().filter(a -> a.g1 != null).sorted(Comparator.comparingDouble(a -> a.g1)).collect(Collectors.toList());
        for (int i = 0; i < byG1.size(); i++) byG1.get(i).g1Rank = i + 1;
        return v;
    }

    @Test
    void frontRunnerWinsAndPickFades() {
        List<EntryView> es = new ArrayList<>();
        es.add(h(3, "선행왕", 1, 1, 1, 13.0, 0.0, 0.20));
        es.add(h(5, "가치마픽", 6, 2, 2, 13.9, 0.9, 0.35));     // 가치마 1위: 앞에서 갔다가 밀림
        es.add(h(7, "중간마", 2, 4, 3, 13.1, 0.2, 0.10));
        es.add(h(8, "막판왕", 4, 8, 7, 12.6, 0.5, 0.05));       // 막판 가장 빠름, 초반 뒤
        es.add(h(1, "보통마", 3, 3, 4, 13.2, 0.3, 0.15));
        es.add(h(2, "느린마", 5, 6, 6, 13.5, 0.7, 0.08));
        es.add(h(4, "꼴찌마", 7, 5, 5, 14.0, 1.4, 0.04));
        es.add(h(6, "후미마", 8, 7, 8, 13.8, 1.8, 0.03));
        RaceRecap r = RaceRecap.of(view(es));
        assertNotNull(r);
        assertTrue(r.headline.startsWith("앞에서 간 말이 끝까지 버틴 경주"), r.headline);
        assertTrue(r.lines.get(0).contains("우승 3번 선행왕 — 초반 1위 → 4코너 1위 → 1착"), r.lines.get(0));
        assertTrue(r.lines.get(1).contains("가치마 1위 5번 가치마픽 (6착): 초반 2위로 앞에서 갔지만"), r.lines.get(1));
        assertTrue(r.lines.get(1).contains("1착과 0.9초"), r.lines.get(1));
        assertTrue(r.lines.get(2).startsWith("다음에 볼 말: 8번 막판왕 (4착)"), r.lines.get(2));
        assertEquals(8, r.rows.size());
        assertTrue(r.hasC4);
        assertTrue(r.rows.get(5).pick);
        assertEquals("+0.9", r.rows.get(5).getGapText());
        assertEquals("–", r.rows.get(0).getGapText());
    }

    @Test
    void noSectionalData() {
        List<EntryView> es = new ArrayList<>();
        for (int i = 1; i <= 5; i++) es.add(h(i, "말" + i, i, null, null, null, null, 0.2));
        assertNull(RaceRecap.of(view(es)));
    }

    @Test
    void particle() {
        assertEquals("이", RaceRecap.ga("마스터플랜"));
        assertEquals("가", RaceRecap.ga("진한파도"));
    }
}
