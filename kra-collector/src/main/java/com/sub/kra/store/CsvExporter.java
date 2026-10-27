package com.sub.kra.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * raw/{api}/meetN/yyyy/yyyyMMdd.json 들을 모아 {outputDir}/{api}.csv 로 내보냄.
 * 칼럼은 응답에 나온 필드 전체(처음 나온 순서). 엑셀 한글 깨짐 방지를 위해 UTF-8 BOM 포함.
 */
@Component
public class CsvExporter {

    private static final Logger log = LoggerFactory.getLogger(CsvExporter.class);
    private final ObjectMapper om;

    public CsvExporter(ObjectMapper om) { this.om = om; }

    public File export(File rawRoot, String api, File outDir) throws IOException {
        File apiDir = new File(rawRoot, api);
        List<File> files = new ArrayList<File>();
        collect(apiDir, files);
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) { return a.getPath().compareTo(b.getPath()); }
        });
        LinkedHashSet<String> cols = new LinkedHashSet<String>();
        // 1차: 칼럼 수집
        for (File f : files) for (JsonNode n : om.readTree(f)) {
            Iterator<String> it = n.fieldNames();
            while (it.hasNext()) cols.add(it.next());
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("폴더 생성 실패: " + outDir);
        File out = new File(outDir, api + ".csv");
        int rows = 0;
        Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8));
        try {
            w.write('﻿');
            w.write("src_meet,src_date");
            for (String c : cols) { w.write(','); w.write(esc(c)); }
            w.write('\n');
            for (File f : files) {
                String meet = f.getParentFile().getParentFile().getName().replace("meet", "");
                String date = f.getName().replace(".json", "");
                for (JsonNode n : om.readTree(f)) {
                    w.write(meet); w.write(','); w.write(date);
                    for (String c : cols) {
                        w.write(',');
                        JsonNode v = n.get(c);
                        if (v != null && !v.isNull()) w.write(esc(v.isValueNode() ? v.asText() : v.toString()));
                    }
                    w.write('\n');
                    rows++;
                }
            }
        } finally { w.close(); }
        log.info("CSV 내보내기 {} → {} ({}행, {}칼럼, {}일)", api, out.getPath(), rows, cols.size(), files.size());
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".json")) out.add(f);
        }
    }

    static String esc(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
