package com.sub.gachima.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 가이드(읽을거리) 글. 본문은 간단한 마크다운 */
@Entity
@Table(name = "post")
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주소용 영문 이름: /guide/{slug} */
    @Column(length = 80, unique = true, nullable = false)
    private String slug;

    @Column(length = 120, nullable = false)
    private String title;

    /** 목록·검색 설명용 한두 문장 */
    @Column(length = 300)
    private String summary;

    @Lob
    @Column(name = "body")
    private String body;

    private boolean published;

    /** 목록 순서 (작을수록 위) */
    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
