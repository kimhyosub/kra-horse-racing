package com.sub.gachima.web;

import com.sub.gachima.domain.MeetDay;
import com.sub.gachima.service.ImportScanner;
import com.sub.gachima.service.ImportService;
import com.sub.gachima.service.RaceService;
import com.sub.gachima.service.StatsService;
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

    private final RaceService raceService;
    private final ImportService importService;
    private final ImportScanner scanner;
    private final StatsService statsService;

    public RaceController(RaceService raceService, ImportService importService, ImportScanner scanner, StatsService statsService) {
        this.raceService = raceService; this.importService = importService; this.scanner = scanner; this.statsService = statsService;
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

    @GetMapping("/stats")
    public String stats(Model model) {
        model.addAttribute("s", statsService.compute());
        return "stats";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("days", raceService.allMeetDays());
        model.addAttribute("dir", importService.getDir().getAbsolutePath());
        model.addAttribute("logs", scanner.history());
        model.addAttribute("auto", scanner.isAuto());
        model.addAttribute("collect", scanner.collectStatus());
        return "admin";
    }

    /** 바뀐 파일만 불러오기. force=true 면 전부 다시 */
    @PostMapping("/admin/import")
    public String importAll(@RequestParam(defaultValue = "false") boolean force, RedirectAttributes ra) {
        ra.addFlashAttribute("msg", scanner.scan(force).message());
        return "redirect:/admin";
    }
}
