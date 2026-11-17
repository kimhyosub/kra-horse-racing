package com.sub.gachima.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.gachima.domain.ImportLog;
import com.sub.gachima.domain.Repositories.ImportLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 불러오기 폴더를 훑어서 새로 생기거나 바뀐 day-*.json 만 불러온다.
 * export_web.py 로 파일을 쓰기만 하면 1분 안에 웹에 들어온다 (gachima.auto-import=false 로 끌 수 있음).
 */
@Component
public class ImportScanner {
    private static final Logger log = LoggerFactory.getLogger(ImportScanner.class);

    private final ImportService importService;
    private final ImportLogRepository logs;
    private final boolean auto;
    private final ObjectMapper om;

    public ImportScanner(ImportService importService, ImportLogRepository logs, ObjectMapper om,
                         @Value("${gachima.auto-import:true}") boolean auto) {
        this.importService = importService; this.logs = logs; this.om = om; this.auto = auto;
    }

    public boolean isAuto() { return auto; }

    @Scheduled(initialDelayString = "${gachima.scan-initial-ms:5000}", fixedDelayString = "${gachima.scan-ms:60000}")
    public void autoScan() {
        if (!auto) return;
        Result r = scan(false);
        if (!r.ok.isEmpty() || !r.fail.isEmpty()) log.info("자동 불러오기: {}", r.message());
    }

    /** force=true 면 바뀌지 않은 파일도 전부 다시 불러온다 */
    public synchronized Result scan(boolean force) {
        Result r = new Result();
        File dir = importService.getDir();
        File[] files = dir.listFiles((d, n) -> n.startsWith("day-") && n.endsWith(".json"));
        if (files == null) { r.missingDir = true; r.dir = dir.getAbsolutePath(); return r; }
        r.dir = dir.getAbsolutePath();
        Arrays.sort(files);
        for (File f : files) {
            ImportLog l = logs.findById(f.getName()).orElse(null);
            if (!force && l != null && l.isOk() && l.getLastModified() == f.lastModified() && l.getFileSize() == f.length()) {
                r.skipped++;
                continue;
            }
            if (l == null) { l = new ImportLog(); l.setFileName(f.getName()); }
            l.setLastModified(f.lastModified()); l.setFileSize(f.length()); l.setImportedAt(LocalDateTime.now());
            try {
                importService.importFile(f);
                l.setStatus("ok"); l.setMessage(null);
                r.ok.add(f.getName());
            } catch (Exception e) {
                log.error("불러오기 실패 {}", f.getName(), e);
                String m = rootMessage(e);
                l.setStatus("fail"); l.setMessage(m.length() > 480 ? m.substring(0, 480) : m);
                r.fail.add(f.getName() + " → " + m);
            }
            logs.save(l);
        }
        r.total = files.length;
        return r;
    }

    public List<ImportLog> history() { return logs.findAllByOrderByFileNameDesc(); }

    /** automation/kra-daily.ps1 이 남기는 마지막 자동 수집 결과 (없으면 null) */
    public Map<String, Object> collectStatus() {
        File f = new File(importService.getDir(), "collect-status.json");
        if (!f.isFile()) return null;
        try { return om.readValue(f, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { log.warn("collect-status.json 읽기 실패: {}", e.getMessage()); return null; }
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    public static class Result {
        public final List<String> ok = new ArrayList<>(), fail = new ArrayList<>();
        public int skipped, total;
        public boolean missingDir;
        public String dir;

        public String message() {
            if (missingDir) return "폴더가 없습니다: " + dir + " (폴더를 만들거나 GACHIMA_IMPORT_DIR 을 바꾸세요)";
            if (total == 0) return "폴더에 day-YYYYMMDD.json 파일이 없습니다: " + dir;
            StringBuilder b = new StringBuilder();
            if (!ok.isEmpty()) b.append("불러옴 ").append(ok.size()).append("개: ").append(String.join(", ", ok)).append(". ");
            if (skipped > 0) b.append("바뀌지 않아 건너뜀 ").append(skipped).append("개. ");
            if (!fail.isEmpty()) b.append("실패: ").append(String.join(" / ", fail));
            return b.toString().trim();
        }
    }
}
