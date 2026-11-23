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

/** 검색엔진·광고용 파일: robots.txt, sitemap.xml, rss.xml, ads.txt */
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
        String daum = site.get().daumVerify;
        return "User-agent: *\nDisallow: /admin\nDisallow: /login\nAllow: /\n\nSitemap: " + base(req) + "/sitemap.xml\n"
                + (daum.isEmpty() ? "" : "\n" + daum + "\n");
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

    /** 가이드 글 RSS 2.0 (네이버 서치어드바이저 'RSS 제출'용). 최신 글 30개 */
    @GetMapping(value = "/rss.xml", produces = "application/rss+xml;charset=UTF-8")
    public String rss(HttpServletRequest req) {
        String b = base(req);
        java.time.format.DateTimeFormatter rfc = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
        java.time.ZoneId kst = java.time.ZoneId.of("Asia/Seoul");
        java.util.List<com.sub.gachima.domain.Post> list = posts.published(null);
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n<channel>\n");
        x.append("  <title>가치마 가이드</title>\n  <link>").append(b).append("/guide</link>\n");
        x.append("  <atom:link href=\"").append(b).append("/rss.xml\" rel=\"self\" type=\"application/rss+xml\"/>\n");
        x.append("  <description>공공데이터로 만든 경마 분석 가치마의 기초 가이드·데이터 분석·주간 총평</description>\n  <language>ko</language>\n");
        if (!list.isEmpty() && list.get(0).getCreatedAt() != null)
            x.append("  <lastBuildDate>").append(rfc.format(list.get(0).getCreatedAt().atZone(kst))).append("</lastBuildDate>\n");
        int n = 0;
        for (com.sub.gachima.domain.Post p : list) {
            if (n++ >= 30) break;
            String link = b + "/guide/" + p.getSlug();
            x.append("  <item>\n    <title>").append(xml(p.getTitle())).append("</title>\n    <link>").append(link).append("</link>\n");
            x.append("    <guid isPermaLink=\"true\">").append(link).append("</guid>\n");
            x.append("    <category>").append(xml(p.getCategoryLabel())).append("</category>\n");
            if (p.getSummary() != null) x.append("    <description>").append(xml(p.getSummary())).append("</description>\n");
            if (p.getCreatedAt() != null) x.append("    <pubDate>").append(rfc.format(p.getCreatedAt().atZone(kst))).append("</pubDate>\n");
            x.append("  </item>\n");
        }
        return x.append("</channel>\n</rss>\n").toString();
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void url(StringBuilder x, String loc, String lastmod, String freq) {
        x.append("  <url><loc>").append(loc).append("</loc>");
        if (lastmod != null) x.append("<lastmod>").append(lastmod).append("</lastmod>");
        if (freq != null) x.append("<changefreq>").append(freq).append("</changefreq>");
        x.append("</url>\n");
    }
}
