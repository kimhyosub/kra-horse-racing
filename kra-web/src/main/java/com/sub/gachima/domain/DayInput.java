package com.sub.gachima.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 당일 입력 한 번 (배당·마체중·주로). 경주마다 여러 번 쌓이고, 가장 최근 것으로 계산한다. */
@Entity
@Table(name = "day_input", indexes = @Index(columnList = "race_id,created_at"))
public class DayInput {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "race_id")
    private Race race;

    /** 건조/양호/다습/포화/불량 */
    @Column(length = 4)
    private String track;

    /** {"마번": 배당} — 빈 값이나 없는 마번은 출전 취소로 본다 */
    @Lob
    private String oddsJson;

    /** {"마번": 마체중 증감 kg} */
    @Lob
    private String bwdJson;

    /** manual(직접 입력) / final(확정 최종 배당) / screenshot */
    @Column(length = 20)
    private String source;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public Race getRace() { return race; }
    public void setRace(Race race) { this.race = race; }
    public String getTrack() { return track; }
    public void setTrack(String track) { this.track = track; }
    public String getOddsJson() { return oddsJson; }
    public void setOddsJson(String oddsJson) { this.oddsJson = oddsJson; }
    public String getBwdJson() { return bwdJson; }
    public void setBwdJson(String bwdJson) { this.bwdJson = bwdJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
