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
