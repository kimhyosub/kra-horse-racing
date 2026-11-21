package com.sub.gachima.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import javax.servlet.http.HttpServletRequest;

/**
 * 모든 화면 공통: 공개 주소(검색·공유 링크), 애드센스 게시자 ID, 서치 콘솔 확인 코드, 연락처.
 * 값은 환경변수로: GACHIMA_PUBLIC_URL, GACHIMA_ADSENSE_CLIENT(ca-pub-…), GACHIMA_GSC_VERIFY, GACHIMA_CONTACT
 */
@ControllerAdvice
public class SiteInfo {

    public static class Site {
        public final String url, adsClient, gscVerify, contact;
        Site(String url, String adsClient, String gscVerify, String contact) {
            this.url = url; this.adsClient = adsClient; this.gscVerify = gscVerify; this.contact = contact;
        }
        public boolean isAds() { return !adsClient.isEmpty(); }
        /** ads.txt 용: ca-pub-123 → pub-123 */
        public String getPubId() { return adsClient.startsWith("ca-") ? adsClient.substring(3) : adsClient; }
    }

    private final Site site;

    public SiteInfo(@Value("${gachima.public-url:}") String url,
                    @Value("${gachima.ads.client:}") String ads,
                    @Value("${gachima.ads.gsc-verify:}") String gsc,
                    @Value("${gachima.ads.contact:}") String contact) {
        this.site = new Site(trim(url).replaceAll("/+$", ""), trim(ads), trim(gsc), trim(contact));
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    public Site get() { return site; }

    @ModelAttribute("site")
    public Site site() { return site; }

    /** 대표 주소(canonical): 공개 주소 + 경로 (쿼리 제외) */
    @ModelAttribute("canonical")
    public String canonical(HttpServletRequest req) {
        String base = site.url.isEmpty() ? req.getScheme() + "://" + req.getServerName() + (req.getServerPort() == 80 || req.getServerPort() == 443 ? "" : ":" + req.getServerPort()) : site.url;
        return base + req.getRequestURI();
    }
}
