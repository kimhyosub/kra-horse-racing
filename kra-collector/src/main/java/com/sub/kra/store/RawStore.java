package com.sub.kra.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.kra.config.KraProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 원본 JSON 저장소: {outputDir}/raw/{api}/meet{n}/{yyyy}/{yyyyMMdd}.json
 * 데이터가 없는 날도 [] 로 저장해서 재실행 시 다시 호출하지 않음.
 */
@Component
public class RawStore {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final File root;
    private final ObjectMapper om;

    public RawStore(KraProperties props, ObjectMapper om) {
        this.root = new File(props.getOutputDir(), "raw");
        this.om = om;
    }

    public File file(String api, int meet, LocalDate date) {
        return new File(root, api + File.separator + "meet" + meet + File.separator + date.getYear()
                + File.separator + date.format(YMD) + ".json");
    }

    public boolean exists(String api, int meet, LocalDate date) { return file(api, meet, date).isFile(); }

    /** 확정된 파일인가: 경주일 다음 날 이후에 저장된 파일만 확정으로 본다.
     *  경주 당일(또는 그 전)에 받은 파일은 경주 전 데이터(착순·배당 없음)일 수 있으므로 다시 받는다. */
    public boolean isFinal(String api, int meet, LocalDate date) {
        File f = file(api, meet, date);
        if (!f.isFile()) return false;
        LocalDate saved = java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return saved.isAfter(date);
    }

    public void save(String api, int meet, LocalDate date, List<JsonNode> items) throws IOException {
        File f = file(api, meet, date);
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("폴더 생성 실패: " + dir);
        File tmp = new File(dir, f.getName() + ".tmp");
        om.writerWithDefaultPrettyPrinter().writeValue(tmp, items);
        if (f.exists() && !f.delete()) throw new IOException("기존 파일 삭제 실패: " + f);
        if (!tmp.renameTo(f)) throw new IOException("파일 저장 실패: " + f);
    }

    public File getRoot() { return root; }
}
