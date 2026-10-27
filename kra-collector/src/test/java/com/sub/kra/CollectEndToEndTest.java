package com.sub.kra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.kra.client.KraApiClient;
import com.sub.kra.collect.CollectService;
import com.sub.kra.config.KraProperties;
import com.sub.kra.store.CsvExporter;
import com.sub.kra.store.RawStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 로컬 가짜 서버로 페이징/저장/이어받기/한도/CSV 를 검증 */
class CollectEndToEndTest {

    static HttpServer server;
    static AtomicInteger hits = new AtomicInteger();
    static final List<String> queries = Collections.synchronizedList(new ArrayList<String>());

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/race", ex -> {
            hits.incrementAndGet();
            String q = ex.getRequestURI().getRawQuery();
            queries.add(q);
            Map<String, String> m = new HashMap<String, String>();
            for (String kv : q.split("&")) { String[] a = kv.split("=", 2); m.put(a[0], a.length > 1 ? a[1] : ""); }
            String date = m.get("rc_date"); int page = Integer.parseInt(m.get("pageNo")); int rows = Integer.parseInt(m.get("numOfRows"));
            String body;
            if (date.endsWith("02")) {                       // 토요일만 경주 있음: 3행, 페이지 크기 2 → 2페이지
                int total = 3; StringBuilder sb = new StringBuilder("[");
                for (int i = (page - 1) * rows; i < Math.min(total, page * rows); i++) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append("{\"rcDate\":\"").append(date).append("\",\"chulNo\":").append(i + 1)
                      .append(",\"hrName\":\"말,").append(i).append("\",\"ord\":").append(i + 1).append('}');
                }
                body = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + sb + "]},\"totalCount\":" + total + "}}}";
            } else body = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":\"\",\"totalCount\":0}}}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            OutputStream os = ex.getResponseBody(); os.write(b); os.close();
        });
        server.start();
    }
    @AfterAll static void stop() { server.stop(0); }

    /** 테스트 실행 순서와 무관하도록 호출 기록 초기화 */
    @BeforeEach void reset() { hits.set(0); queries.clear(); }

    KraProperties props(Path dir) {
        KraProperties p = new KraProperties();
        p.setServiceKey("abc%2Bdef%3D%3D");   // 이미 인코딩된 키
        p.setFrom("2026-05-01"); p.setTo("2026-05-03");
        p.setOutputDir(dir.toString()); p.setNumOfRows(2); p.setRequestDelayMs(0);
        KraProperties.ApiDef d = new KraProperties.ApiDef();
        d.setUrl("http://localhost:" + server.getAddress().getPort() + "/race");
        d.getExtraParams().put("_type", "json");
        p.getApis().put("race-result", d);
        return p;
    }

    @Test void collectResumeAndExport(@TempDir Path dir) throws Exception {
        ObjectMapper om = new ObjectMapper();
        KraProperties p = props(dir);
        RawStore store = new RawStore(p, om);
        CollectService.Summary s = new CollectService(p, new KraApiClient(p, om), store).run();
        assertEquals(3, s.saved); assertEquals(3, s.rows); assertEquals(2, s.emptyDays);
        assertEquals(4, hits.get());                         // 빈 날 2 + 경주일 2페이지
        assertTrue(queries.get(0).startsWith("serviceKey=abc%2Bdef%3D%3D&"), "키 이중 인코딩 없어야 함: " + queries.get(0));

        // 재실행 → 모두 건너뜀
        CollectService.Summary s2 = new CollectService(p, new KraApiClient(p, om), store).run();
        assertEquals(3, s2.skipped); assertEquals(4, hits.get());

        File csv = new CsvExporter(om).export(store.getRoot(), "race-result", dir.toFile());
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
        assertEquals("﻿src_meet,src_date,rcDate,chulNo,hrName,ord", lines.get(0));
        assertEquals("1,20260502,20260502,1,\"말,0\",1", lines.get(1));
    }

    @Test void stopsAtDailyLimit(@TempDir Path dir) {
        ObjectMapper om = new ObjectMapper();
        KraProperties p = props(dir); p.setDailyLimit(2);
        CollectService.Summary s = new CollectService(p, new KraApiClient(p, om), new RawStore(p, om)).run();
        assertTrue(s.stoppedByLimit);
        assertEquals(1, s.saved);                            // 5/1(빈 날)만 저장, 5/2 도중 한도
    }
}
