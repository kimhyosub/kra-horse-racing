package com.sub.gachima.web;

import com.sub.gachima.domain.MeetDay;
import com.sub.gachima.service.ImportService;
import com.sub.gachima.service.RaceService;
import com.sub.gachima.service.RaceService.RaceView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
public class RaceController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RaceController.class);

    private final RaceService raceService;
    private final ImportService importService;

    public RaceController(RaceService raceService, ImportService importService) {
        this.raceService = raceService; this.importService = importService;
    }

    /** 가장 최근 경주일의 첫 경주로 */
    @GetMapping("/")
    public String home() {
        Optional<MeetDay> md = raceService.latest();
        return md.map(m -> "redirect:/r/" + m.getRaceDate() + "/" + m.getMeet() + "/1").orElse("redirect:/admin");
    }

    @GetMapping("/r/{date}/{meet}/{rc}")
    public String race(@PathVariable String date, @PathVariable int meet, @PathVariable int rc,
                       @RequestParam(defaultValue = "pre") String mode,
                       @RequestParam(defaultValue = "p") String sort,
                       @RequestParam(defaultValue = "false") boolean edit, Model model) {
        RaceView v = raceService.view(date, meet, rc)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "경주가 없습니다"));
        model.addAttribute("v", v);
        model.addAttribute("mode", "day".equals(mode) ? "day" : "pre");
        model.addAttribute("sort", "v".equals(sort) ? "v" : "p");
        model.addAttribute("edit", edit || ("day".equals(mode) && !v.isScored()));
        model.addAttribute("tracks", RaceService.TRACKS);
        return "race";
    }

    /** 당일 배당·마체중·주로 입력. 폼 이름: odds_{마번}, bwd_{마번}, track */
    @PostMapping("/r/{date}/{meet}/{rc}/input")
    public String input(@PathVariable String date, @PathVariable int meet, @PathVariable int rc,
                        @RequestParam Map<String, String> form) {
        Map<Integer, String> odds = new HashMap<>(), bwd = new HashMap<>();
        for (Map.Entry<String, String> e : form.entrySet()) {
            String k = e.getKey();
            try {
                if (k.startsWith("odds_")) odds.put(Integer.parseInt(k.substring(5)), e.getValue());
                else if (k.startsWith("bwd_")) bwd.put(Integer.parseInt(k.substring(4)), e.getValue());
            } catch (NumberFormatException ignore) { /* 다른 칸 */ }
        }
        raceService.saveInput(date, meet, rc, form.get("track"), odds, bwd);
        return "redirect:/r/" + date + "/" + meet + "/" + rc + "?mode=day";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("days", raceService.allMeetDays());
        model.addAttribute("dir", importService.getDir().getAbsolutePath());
        return "admin";
    }

    @PostMapping("/admin/import")
    public String importAll(RedirectAttributes ra) {
        java.io.File dir = importService.getDir();
        java.io.File[] files = dir.listFiles((d, n) -> n.startsWith("day-") && n.endsWith(".json"));
        if (files == null || files.length == 0) {
            ra.addFlashAttribute("msg", dir.isDirectory()
                    ? "폴더에 day-YYYYMMDD.json 파일이 없습니다: " + dir.getAbsolutePath()
                    : "폴더가 없습니다: " + dir.getAbsolutePath() + " (폴더를 만들거나 GACHIMA_IMPORT_DIR 을 바꾸세요)");
            return "redirect:/admin";
        }
        Arrays.sort(files);
        List<String> ok = new ArrayList<>(), fail = new ArrayList<>();
        for (java.io.File f : files) {
            try {
                importService.importFile(f);
                ok.add(f.getName());
            } catch (Exception e) {
                log.error("불러오기 실패 {}", f.getName(), e);
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                fail.add(f.getName() + " → " + root.getClass().getSimpleName() + ": " + root.getMessage());
            }
        }
        StringBuilder msg = new StringBuilder();
        if (!ok.isEmpty()) msg.append("불러옴: ").append(String.join(", ", ok)).append(". ");
        if (!fail.isEmpty()) msg.append("실패: ").append(String.join(" / ", fail));
        ra.addFlashAttribute("msg", msg.toString().trim());
        return "redirect:/admin";
    }
}
