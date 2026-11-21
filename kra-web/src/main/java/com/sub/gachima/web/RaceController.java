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
    private final com.sub.gachima.service.PaperService paperService;
    private final com.sub.gachima.service.ScreenshotReader screenshotReader;
    private final com.sub.gachima.telegram.TelegramBot telegram;
    private final com.sub.gachima.service.ModelInfo modelInfo;

    public RaceController(RaceService raceService, ImportService importService, ImportScanner scanner, StatsService statsService,
                          com.sub.gachima.service.PaperService paperService, com.sub.gachima.service.ScreenshotReader screenshotReader,
                          com.sub.gachima.telegram.TelegramBot telegram, com.sub.gachima.service.ModelInfo modelInfo) {
        this.raceService = raceService; this.importService = importService; this.scanner = scanner; this.statsService = statsService;
        this.paperService = paperService; this.screenshotReader = screenshotReader; this.telegram = telegram; this.modelInfo = modelInfo;
    }

    @GetMapping("/login")
    public String login() { return "login"; }

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
        Object shot = model.asMap().get(ScreenshotController.FLASH);
        boolean prefilled = shot instanceof ScreenshotController.Prefill && ((ScreenshotController.Prefill) shot).matches(date, meet, rc);
        if (prefilled) ((ScreenshotController.Prefill) shot).applyTo(v);
        else model.asMap().remove(ScreenshotController.FLASH);
        model.addAttribute("v", v);
        model.addAttribute("shotEnabled", screenshotReader.isEnabled());
        model.addAttribute("mode", "day".equals(mode) || prefilled ? "day" : "pre");
        model.addAttribute("sort", "v".equals(sort) ? "v" : "p");
        model.addAttribute("edit", edit || prefilled || ("day".equals(mode) && !v.isScored()));
        model.addAttribute("tracks", RaceService.TRACKS);
        return "race";
    }

    /** 가중치: 전날 모델·당일 모델(경마장별) 요인 가중치 */
    @GetMapping("/model")
    public String model(@RequestParam(required = false) String date, @RequestParam(required = false) Integer meet, Model model) {
        com.sub.gachima.domain.MeetDay md = modelInfo.find(date, meet)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "경주일이 없습니다"));
        model.addAttribute("md", md);
        model.addAttribute("meets", modelInfo.meetsOn(md.getRaceDate()));
        model.addAttribute("pre", modelInfo.pre(md));
        model.addAttribute("day", modelInfo.day(md));
        return "model";
    }

    /** 점수표: 말 × 요인 점수(0~100, 50 = 경주 평균)와 근거 숫자 */
    @GetMapping("/r/{date}/{meet}/{rc}/score")
    public String score(@PathVariable String date, @PathVariable int meet, @PathVariable int rc, Model model) {
        RaceView v = raceService.view(date, meet, rc)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "경주가 없습니다"));
        model.addAttribute("v", v);
        model.addAttribute("t", com.sub.gachima.service.ScoreTable.of(v));
        return "score";
    }

    /** 당일 배당·마체중·주로 입력. 폼 이름: odds_{마번}, bwd_{마번}, track */
    @PostMapping("/r/{date}/{meet}/{rc}/input")
    public String input(@PathVariable String date, @PathVariable int meet, @PathVariable int rc,
                        @RequestParam Map<String, String> form, RedirectAttributes ra, java.security.Principal who) {
        Map<Integer, String> odds = new HashMap<>(), bwd = new HashMap<>();
        for (Map.Entry<String, String> e : form.entrySet()) {
            String k = e.getKey();
            try {
                if (k.startsWith("odds_")) odds.put(Integer.parseInt(k.substring(5)), e.getValue());
                else if (k.startsWith("bwd_")) bwd.put(Integer.parseInt(k.substring(4)), e.getValue());
            } catch (NumberFormatException ignore) { /* 다른 칸 */ }
        }
        com.sub.gachima.service.PaperService.Outcome o = raceService.saveInput(date, meet, rc, form.get("track"), odds, bwd, who == null ? null : who.getName());
        // 출발 전 입력이면 텔레그램 대화방에도 당일 분석 요약을 보낸다
        if (o == com.sub.gachima.service.PaperService.Outcome.RECORDED) telegram.notifyAll(date, meet, rc, who == null ? null : who.getName());
        switch (o) {
            case RECORDED: ra.addFlashAttribute("paperMsg", "모의 기록을 이번 입력의 ★ 로 바꿨습니다. 출발 전 마지막 입력이 공식 기록입니다."); break;
            case LOCKED_RESULT: ra.addFlashAttribute("paperMsg", "결과가 나온 경주라 모의 기록은 바뀌지 않았습니다 (분석만 다시 계산)."); break;
            default: ra.addFlashAttribute("paperMsg", "출발 시각이 지나 모의 기록은 바뀌지 않았습니다 (분석만 다시 계산)."); break;
        }
        return "redirect:/r/" + date + "/" + meet + "/" + rc + "?mode=day";
    }

    @GetMapping("/stats")
    public String stats(Model model) {
        model.addAttribute("s", statsService.compute());
        model.addAttribute("ledger", paperService.ledger());
        return "stats";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("days", raceService.allMeetDays());
        model.addAttribute("dir", importService.getDir().getAbsolutePath());
        model.addAttribute("logs", scanner.history());
        model.addAttribute("auto", scanner.isAuto());
        model.addAttribute("collect", scanner.collectStatus());
        model.addAttribute("shotEnabled", screenshotReader.isEnabled());
        model.addAttribute("tgEnabled", telegram.isEnabled());
        model.addAttribute("tgChats", telegram.getChats().size());
        return "admin";
    }

    /** 바뀐 파일만 불러오기. force=true 면 전부 다시 */
    @PostMapping("/admin/import")
    public String importAll(@RequestParam(defaultValue = "false") boolean force, RedirectAttributes ra) {
        ra.addFlashAttribute("msg", scanner.scan(force).message());
        return "redirect:/admin";
    }
}
