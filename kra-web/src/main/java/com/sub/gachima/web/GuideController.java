package com.sub.gachima.web;

import com.sub.gachima.domain.Post;
import com.sub.gachima.service.Markdown;
import com.sub.gachima.service.PostService;
import com.sub.gachima.service.UploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import java.util.Collections;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 가이드(읽을거리): 누구나 읽기, 관리자만 쓰기·고치기 (/admin/posts) */
@Controller
public class GuideController {

    private final PostService posts;
    private final UploadService uploads;
    private final SiteInfo site;

    public GuideController(PostService posts, UploadService uploads, SiteInfo site) { this.posts = posts; this.uploads = uploads; this.site = site; }

    @GetMapping("/guide")
    public String list(@RequestParam(required = false) String cat, Model model) {
        if (cat != null && !Post.CATS.containsKey(cat)) cat = null;
        model.addAttribute("posts", posts.published(cat));
        model.addAttribute("cat", cat);
        model.addAttribute("cats", Post.CATS);
        return "guide-list";
    }

    @GetMapping("/guide/{slug}")
    public String read(@PathVariable String slug, Model model, Authentication auth) {
        Post p = posts.bySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "글이 없습니다"));
        boolean admin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!p.isPublished() && !admin) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "글이 없습니다");
        model.addAttribute("p", p);
        model.addAttribute("html", Markdown.toHtml(p.getBody()));
        model.addAttribute("desc", p.getSummary() != null && !p.getSummary().isEmpty() ? p.getSummary() : Markdown.plain(p.getBody(), 150));
        java.util.List<Post> others = new java.util.ArrayList<>();
        for (Post o : posts.published(p.getCategory())) if (!o.getId().equals(p.getId())) others.add(o);
        model.addAttribute("others", others.size() > 6 ? others.subList(0, 6) : others);
        if (p.getCover() != null) model.addAttribute("ogImage", p.getCover().startsWith("http") ? p.getCover() : site.get().url + p.getCover());
        return "guide";
    }

    // ---------------- 관리자: 글쓰기 ----------------

    @GetMapping("/admin/posts")
    public String admin(Model model) {
        model.addAttribute("posts", posts.all());
        return "post-admin";
    }

    @GetMapping("/admin/posts/new")
    public String create(Model model) {
        model.addAttribute("p", new Post());
        return "post-edit";
    }

    @GetMapping("/admin/posts/{id}")
    public String edit(@PathVariable long id, Model model) {
        model.addAttribute("p", posts.byId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "글이 없습니다")));
        return "post-edit";
    }

    @PostMapping("/admin/posts")
    public String save(@RequestParam(required = false) Long id, @RequestParam String slug, @RequestParam String title,
                       @RequestParam(required = false) String summary, @RequestParam(required = false) String body,
                       @RequestParam(defaultValue = "false") boolean published, @RequestParam(defaultValue = "100") int sortOrder,
                       @RequestParam(required = false) String cover, @RequestParam(required = false) String category,
                       Model model, RedirectAttributes ra) {
        try {
            Post p = posts.save(id, slug, title, summary, body, published, sortOrder, cover, category);
            ra.addFlashAttribute("msg", published ? "저장했습니다 (공개)" : "저장했습니다 (비공개 초안)");
            return "redirect:/guide/" + p.getSlug();
        } catch (IllegalArgumentException e) {
            Post p = id == null ? new Post() : posts.byId(id).orElse(new Post());
            p.setSlug(slug); p.setTitle(title); p.setSummary(summary); p.setBody(body); p.setPublished(published); p.setSortOrder(sortOrder); p.setCover(cover); p.setCategory(category);
            model.addAttribute("p", p);
            model.addAttribute("error", e.getMessage());
            return "post-edit";
        }
    }

    /** 글쓰기 화면의 이미지 올리기 → {"url": "/uploads/…"} 또는 {"error": "…"} */
    @PostMapping(value = "/admin/posts/upload", produces = "application/json;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(Collections.singletonMap("url", uploads.save(file)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", "저장 실패: " + e.getMessage()));
        }
    }

    @PostMapping("/admin/posts/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes ra) {
        posts.delete(id);
        ra.addFlashAttribute("msg", "글을 지웠습니다");
        return "redirect:/admin/posts";
    }
}
