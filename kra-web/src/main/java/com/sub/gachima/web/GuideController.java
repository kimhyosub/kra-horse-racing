package com.sub.gachima.web;

import com.sub.gachima.domain.Post;
import com.sub.gachima.service.Markdown;
import com.sub.gachima.service.PostService;
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

    public GuideController(PostService posts) { this.posts = posts; }

    @GetMapping("/guide")
    public String list(Model model) {
        model.addAttribute("posts", posts.published());
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
        model.addAttribute("others", posts.published());
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
                       Model model, RedirectAttributes ra) {
        try {
            Post p = posts.save(id, slug, title, summary, body, published, sortOrder);
            ra.addFlashAttribute("msg", published ? "저장했습니다 (공개)" : "저장했습니다 (비공개 초안)");
            return "redirect:/guide/" + p.getSlug();
        } catch (IllegalArgumentException e) {
            Post p = id == null ? new Post() : posts.byId(id).orElse(new Post());
            p.setSlug(slug); p.setTitle(title); p.setSummary(summary); p.setBody(body); p.setPublished(published); p.setSortOrder(sortOrder);
            model.addAttribute("p", p);
            model.addAttribute("error", e.getMessage());
            return "post-edit";
        }
    }

    @PostMapping("/admin/posts/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes ra) {
        posts.delete(id);
        ra.addFlashAttribute("msg", "글을 지웠습니다");
        return "redirect:/admin/posts";
    }
}
