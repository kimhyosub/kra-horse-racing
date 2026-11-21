package com.sub.gachima.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 모의 기록 한 건 = 가치마(★) 한 마리 단승 1단위.
 * 경주마다 출발 전 마지막 입력의 ★ 가 공식 기록이 되고, 정산은 확정 배당으로 한다.
 * 경주 데이터(Race)가 웹에 없어도 남도록 날짜·경마장·경주·마번으로 묶는다.
 */
@Entity
@Table(name = "paper_bet",
        uniqueConstraints = @UniqueConstraint(columnNames = {"race_date", "meet", "rc_no", "chul_no"}),
        indexes = @Index(columnList = "race_date, meet, rc_no"))
public class PaperBet {
    public static final int REFERENCE_MEET = 4;   // 영천: 참고 기록

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "race_date", length = 8, nullable = false)
    private String raceDate;
    @Column(name = "meet", nullable = false)
    private int meet;
    @Column(name = "rc_no", nullable = false)
    private int rcNo;
    @Column(name = "chul_no", nullable = false)
    private int chulNo;

    @Column(name = "hr_name", length = 40)
    private String hrName;
    /** 기록 시점(출발 전 마지막 입력)의 단승 배당 */
    @Column(name = "bet_odds")
    private double betOdds;
    @Column(name = "p_model")
    private double pModel;
    @Column(name = "ev")
    private double ev;
    @Column(name = "bwd")
    private Double bwd;
    @Column(name = "track", length = 10)
    private String track;
    @Column(name = "placed_at")
    private LocalDateTime placedAt;
    /** 기록한 입력의 아이디 */
    @Column(name = "placed_by", length = 40)
    private String placedBy;
    public String getPlacedBy() { return placedBy; }
    public void setPlacedBy(String placedBy) { this.placedBy = placedBy; }
    public String getPlacedByLabel() { return placedBy == null ? "" : " " + placedBy; }

    /** web(웹 입력) / migrated(예전 모의배팅 DB에서 옮김) */
    @Column(name = "source", length = 10)
    private String source;

    /** 경주 결과가 들어왔는가 */
    @Column(name = "settled")
    private boolean settled;
    @Column(name = "finish_ord")
    private Integer finishOrd;
    @Column(name = "final_odds")
    private Double finalOdds;

    /** 장부 화면용: 이 경주의 데이터가 웹에 있어 링크할 수 있는가 */
    @Transient
    private boolean linked;
    public boolean isLinked() { return linked; }
    public void setLinked(boolean linked) { this.linked = linked; }

    public boolean isOfficial() { return meet != REFERENCE_MEET; }
    /** 결과는 나왔는데 착순이 없음 = 출전 취소·제외 → 건수에서 뺀다 */
    public boolean isScratched() { return settled && finishOrd == null; }
    public boolean isCounted() { return settled && finishOrd != null; }
    public boolean isWin() { return isCounted() && finishOrd == 1; }
    public double getPayout() { return isWin() ? (finalOdds != null ? finalOdds : betOdds) : 0; }
    public String getStatus() {
        if (!settled) return "대기";
        if (finishOrd == null) return "취소";
        return finishOrd == 1 ? "적중" : finishOrd >= Entry.ORD_OUT ? "착외" : finishOrd + "착";
    }
    public String getResultLabel() { return isWin() ? "적중 " + getPayout() + "배" : getStatus(); }
    public String getMeetName() {
        switch (meet) { case 1: return "서울"; case 2: return "제주"; case 3: return "부경"; case 4: return "영천"; default: return String.valueOf(meet); }
    }
    public String getDateLabel() { return raceDate.substring(4, 6) + "." + raceDate.substring(6); }
    /** 기록한 뒤 배당이 얼마나 움직였나 (확정/기록) */
    public Double getDrift() { return finalOdds == null || betOdds <= 0 ? null : finalOdds / betOdds; }
    public boolean isBigDrift() { Double d = getDrift(); return d != null && (d >= 1.5 || d <= 1 / 1.5); }

    public Long getId() { return id; }
    public String getRaceDate() { return raceDate; }
    public void setRaceDate(String raceDate) { this.raceDate = raceDate; }
    public int getMeet() { return meet; }
    public void setMeet(int meet) { this.meet = meet; }
    public int getRcNo() { return rcNo; }
    public void setRcNo(int rcNo) { this.rcNo = rcNo; }
    public int getChulNo() { return chulNo; }
    public void setChulNo(int chulNo) { this.chulNo = chulNo; }
    public String getHrName() { return hrName; }
    public void setHrName(String hrName) { this.hrName = hrName; }
    public double getBetOdds() { return betOdds; }
    public void setBetOdds(double betOdds) { this.betOdds = betOdds; }
    public double getPModel() { return pModel; }
    public void setPModel(double pModel) { this.pModel = pModel; }
    public double getEv() { return ev; }
    public void setEv(double ev) { this.ev = ev; }
    public Double getBwd() { return bwd; }
    public void setBwd(Double bwd) { this.bwd = bwd; }
    public String getTrack() { return track; }
    public void setTrack(String track) { this.track = track; }
    public LocalDateTime getPlacedAt() { return placedAt; }
    public void setPlacedAt(LocalDateTime placedAt) { this.placedAt = placedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isSettled() { return settled; }
    public void setSettled(boolean settled) { this.settled = settled; }
    public Integer getFinishOrd() { return finishOrd; }
    public void setFinishOrd(Integer finishOrd) { this.finishOrd = finishOrd; }
    public Double getFinalOdds() { return finalOdds; }
    public void setFinalOdds(Double finalOdds) { this.finalOdds = finalOdds; }
}
