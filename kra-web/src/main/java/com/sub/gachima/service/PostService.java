package com.sub.gachima.service;

import com.sub.gachima.domain.Post;
import com.sub.gachima.domain.Repositories.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 가이드 글. 처음 실행할 때 classpath:guides/*.md 를 글로 넣는다 (같은 주소의 글이 이미 있으면 건너뜀 → 웹에서 고친 글은 덮어쓰지 않음).
 * 파일 형식: 맨 위 "title: …", "summary: …", "order: 10" 줄, 그 다음 "---", 그 아래 본문(마크다운).
 */
@Service
public class PostService {
    private static final Logger log = LoggerFactory.getLogger(PostService.class);
    public static final String SLUG_RULE = "[a-z0-9][a-z0-9-]{1,78}";

    private final PostRepository posts;

    public PostService(PostRepository posts) { this.posts = posts; }

    public List<Post> published() { return posts.findByPublishedTrueOrderBySortOrderAscCreatedAtDesc(); }
    public List<Post> all() { return posts.findAllByOrderBySortOrderAscCreatedAtDesc(); }
    public Optional<Post> bySlug(String slug) { return posts.findBySlug(slug); }
    public Optional<Post> byId(long id) { return posts.findById(id); }

    @Transactional
    public Post save(Long id, String slug, String title, String summary, String body, boolean published, int order) {
        slug = slug == null ? "" : slug.trim().toLowerCase();
        if (!slug.matches(SLUG_RULE)) throw new IllegalArgumentException("주소는 영문 소문자·숫자·하이픈(-)으로 2~80자: 예) how-to-read");
        if (title == null || title.trim().isEmpty()) throw new IllegalArgumentException("제목을 넣어 주세요");
        Post p = id == null ? new Post() : posts.findById(id).orElseThrow(() -> new IllegalArgumentException("글이 없습니다"));
        Optional<Post> same = posts.findBySlug(slug);
        if (same.isPresent() && !same.get().getId().equals(p.getId())) throw new IllegalArgumentException("같은 주소의 글이 이미 있습니다: " + slug);
        LocalDateTime now = LocalDateTime.now();
        if (p.getCreatedAt() == null) p.setCreatedAt(now);
        p.setUpdatedAt(now);
        p.setSlug(slug); p.setTitle(title.trim()); p.setSummary(summary == null ? null : summary.trim());
        p.setBody(body == null ? "" : body); p.setPublished(published); p.setSortOrder(order);
        return posts.save(p);
    }

    @Transactional
    public void delete(long id) { posts.deleteById(id); }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        try {
            Resource[] rs = new PathMatchingResourcePatternResolver().getResources("classpath:guides/*.md");
            int n = 0;
            for (Resource r : rs) {
                String name = r.getFilename();
                if (name == null) continue;
                String slug = name.replaceAll("\\.md$", "");
                if (posts.findBySlug(slug).isPresent()) continue;
                String text;
                try (InputStream in = r.getInputStream()) { text = new String(readAll(in), StandardCharsets.UTF_8); }
                Post p = parse(slug, text);
                posts.save(p);
                n++;
            }
            if (n > 0) log.info("가이드 글 {}편을 처음 넣었습니다", n);
        } catch (Exception e) {
            log.warn("가이드 글 넣기 실패: {}", e.toString());
        }
    }

    static Post parse(String slug, String text) {
        String t = text.replace("\r\n", "\n");
        int sep = t.indexOf("\n---\n");
        String head = sep < 0 ? "" : t.substring(0, sep), body = sep < 0 ? t : t.substring(sep + 5);
        Post p = new Post();
        p.setSlug(slug); p.setTitle(slug); p.setPublished(true);
        for (String l : head.split("\n")) {
            int c = l.indexOf(':');
            if (c < 0) continue;
            String k = l.substring(0, c).trim(), v = l.substring(c + 1).trim();
            if (k.equals("title")) p.setTitle(v);
            else if (k.equals("summary")) p.setSummary(v);
            else if (k.equals("order")) p.setSortOrder(Integer.parseInt(v));
        }
        p.setBody(body.trim() + "\n");
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now); p.setUpdatedAt(now);
        return p;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }
}
