package com.sub.gachima.web;

import com.sub.gachima.domain.MeetDay;
import com.sub.gachima.domain.Race;
import com.sub.gachima.domain.Repositories.RaceRepository;
import com.sub.gachima.service.RaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/** 검색엔진·광고용 파일: robots.txt, sitemap.xml, ads.txt */
@RestController
public class SeoController {

    private final SiteInfo site;
    private final RaceService raceService;
    private final RaceRepository races;
    private final com.sub.gachima.service.PostService posts;

    public SeoController(SiteInfo site, RaceService raceService, RaceRepository races, com.sub.gachima.service.PostService posts) {
        this.site = site; this.raceService = raceService; this.races = races; this.posts = posts;
    }

    private String base(HttpServletRequest req) {
        String u = site.get().url;
        return u.isEmpty() ? req.getScheme() + "://" + req.getServerName() + (req.getServerPort() == 80 || req.getServerPort() == 443 ? "" : ":" + req.getServerPort()) : u;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String robots(HttpServletRequest req) {
        return "User-agent: *\nDisallow: /admin\nDisallow: /login\nAllow: /\n\nSitemap: " + base(req) + "/sitemap.xml\n";
    }

    /** 애드센스 게시자 ID 가 있을 때만. 없으면 404 */
    @GetMapping(value = "/ads.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ads() {
        if (!site.get().isAds()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("");
        return ResponseEntity.ok("google.com, " + site.get().getPubId() + ", DIRECT, f08c47fec0942fa0\n");
    }

    /** 경주 화면(전날 예상)·성적·가중치·이용안내 */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap(HttpServletRequest req) {
        String b = base(req);
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        url(x, b + "/about", null, "monthly");
        url(x, b + "/stats", null, "daily");
        url(x, b + "/model", null, "weekly");
        url(x, b + "/guide", null, "weekly");
        for (com.sub.gachima.domain.Post p : posts.published())
            url(x, b + "/guide/" + p.getSlug(), p.getUpdatedAt() == null ? null : p.getUpdatedAt().toLocalDate().toString(), "monthly");
        for (MeetDay md : raceService.allMeetDays()) {
            String d = md.getRaceDate(), day = d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6);
            for (Race r : races.findByMeetDayOrderByRcNo(md))
                url(x, b + "/r/" + d + "/" + md.getMeet() + "/" + r.getRcNo(), day, null);
        }
        return x.append("</urlset>\n").toString();
    }

    private static void url(StringBuilder x, String loc, String lastmod, String freq) {
        x.append("  <url><loc>").append(loc).append("</loc>");
        if (lastmod != null) x.append("<lastmod>").append(lastmod).append("</lastmod>");
        if (freq != null) x.append("<changefreq>").append(freq).append("</changefreq>");
        x.append("</url>\n");
    }
}
