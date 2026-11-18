package com.sub.gachima.web;

import com.sub.gachima.service.RaceService;
import com.sub.gachima.service.RaceService.EntryView;
import com.sub.gachima.service.RaceService.RaceView;
import com.sub.gachima.service.ScreenshotReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * 스크린샷 올리기 → Claude 로 읽기 → 입력칸을 채운 화면으로 (저장은 사용자가 확인하고 누른다).
 * 읽은 값은 한 번만 쓰는 flash 로 넘긴다.
 */
@Controller
public class ScreenshotController {
    private static final Logger log = LoggerFactory.getLogger(ScreenshotController.class);
    private static final Set<String> OK_TYPES = new HashSet<>(Arrays.asList("image/png", "image/jpeg", "image/webp", "image/gif"));
    public static final String FLASH = "shot";

    private final RaceService raceService;
    private final ScreenshotReader reader;

    public ScreenshotController(RaceService raceService, ScreenshotReader reader) {
        this.raceService = raceService; this.reader = reader;
    }

    @PostMapping("/r/{date}/{meet}/{rc}/screenshot")
    public String upload(@PathVariable String date, @PathVariable int meet, @PathVariable int rc,
                         @RequestParam(value = "files", required = false) List<MultipartFile> files, RedirectAttributes ra) {
        String back = "redirect:/r/" + date + "/" + meet + "/" + rc + "?mode=day&edit=true";
        RaceView v = raceService.view(date, meet, rc)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "경주가 없습니다"));
        if (!reader.isEnabled()) {
            ra.addFlashAttribute("shotError", "스크린샷 읽기가 꺼져 있습니다. PC에 환경변수 ANTHROPIC_API_KEY 를 넣고 웹을 다시 실행하세요.");
            return back;
        }
        try {
            List<ScreenshotReader.Image> images = new ArrayList<>();
            if (files != null) for (MultipartFile f : files) if (f != null && !f.isEmpty()) images.add(toImage(f));
            if (images.isEmpty()) { ra.addFlashAttribute("shotError", "스크린샷을 골라 주세요."); return back; }
            if (images.size() > 5) { ra.addFlashAttribute("shotError", "한 번에 5장까지 올릴 수 있습니다."); return back; }
            Map<Integer, String> names = new TreeMap<>();
            for (EntryView e : v.entries) names.put(e.no, e.name);
            long t0 = System.currentTimeMillis();
            ScreenshotReader.Reading r = reader.read(images, v.meetName, rc, names);
            log.info("스크린샷 {}장 읽음 {} {}R: 배당 {}두, 마체중 {}두, {}ms",
                    images.size(), v.meetName, rc, r.odds.size(), r.bwd.size(), System.currentTimeMillis() - t0);
            ra.addFlashAttribute(FLASH, new Prefill(date, meet, rc, r, names.keySet()));
        } catch (Exception e) {
            log.warn("스크린샷 읽기 실패 {} {} {}R", date, meet, rc, e);
            ra.addFlashAttribute("shotError", "스크린샷을 읽지 못했습니다: " + e.getMessage());
        }
        return back;
    }

    static ScreenshotReader.Image toImage(MultipartFile f) throws IOException {
        String ct = f.getContentType() == null ? "" : f.getContentType().toLowerCase(Locale.ROOT);
        byte[] data = f.getBytes();
        if (OK_TYPES.contains(ct)) return new ScreenshotReader.Image(data, ct);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));   // bmp 등은 png 로 바꾼다 (HEIC 는 못 읽음)
        if (img == null) throw new IOException("지원하지 않는 이미지 형식입니다 (" + ct + "). 스크린샷(PNG/JPG)으로 올려 주세요");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new ScreenshotReader.Image(out.toByteArray(), "image/png");
    }

    /** 경주 화면에 채워 넣을 값과 알림 */
    public static class Prefill implements java.io.Serializable {
        public final String date; public final int meet, rc;
        public final Map<Integer, Double> odds, bwd;
        public final String track;
        public final List<String> warnings = new ArrayList<>();

        Prefill(String date, int meet, int rc, ScreenshotReader.Reading r, Set<Integer> nos) {
            this.date = date; this.meet = meet; this.rc = rc;
            this.odds = new TreeMap<>(r.odds); this.bwd = new TreeMap<>(r.bwd); this.track = r.track;
            if (r.raceNoSeen != null && r.raceNoSeen != rc)
                warnings.add("화면의 경주 번호(" + r.raceNoSeen + "R)가 이 경주(" + rc + "R)와 다릅니다. 다른 경주 화면인지 확인하세요.");
            List<Integer> missing = new ArrayList<>();
            for (Integer no : nos) if (!r.odds.containsKey(no)) missing.add(no);
            if (!missing.isEmpty())
                warnings.add("배당을 못 읽은 마번: " + missing + " — 직접 넣으세요 (비워 두면 출전 취소로 계산).");
            if (r.track == null) warnings.add("주로 상태는 화면에서 못 읽었습니다. 아래에서 직접 고르세요 (함수율 5% 이하 건조, 6~9 양호, 10~14 다습, 15~19 포화, 20 이상 불량).");
            if (r.notes != null && !r.notes.trim().isEmpty()) warnings.add("읽기 메모: " + r.notes.trim());
        }

        public boolean matches(String d, int m, int r) { return date.equals(d) && meet == m && rc == r; }

        /** 입력칸에 반영. 못 읽은 칸은 비운다 (예전 입력이 남아 있으면 섞이므로) */
        public void applyTo(RaceView v) {
            for (EntryView e : v.entries) {
                e.inputOdds = odds.get(e.no);
                e.inputBwd = bwd.get(e.no);
            }
            if (track != null) v.track = track;
        }

        public int getReadCount() { return odds.size(); }
    }
}
