package com.sub.gachima.domain;

import javax.persistence.*;

/** 출전마 한 두. 전날 예상 결과와 당일 계산에 쓸 요인값을 가진다. */
@Entity
@Table(name = "entry", uniqueConstraints = @UniqueConstraint(columnNames = {"race_id", "chul_no"}))
public class Entry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "race_id")
    private Race race;

    @Column(name = "chul_no")
    private int chulNo;

    @Column(length = 20)
    private String hrNo;

    @Column(length = 40)
    private String hrName;

    @Column(length = 20)
    private String jkName;

    @Column(length = 20)
    private String trName;

    /** 직전 출전 후 일수 (첫 출전은 null) */
    private Double days;

    private boolean firstStart;

    /** 당일 모델 요인값 (JSON 배열, 당일 입력 요인은 null) */
    @Lob
    private String featuresJson;

    /** 전날 예상 근거: 사람이 읽는 기록 (최근 착순, 휴양일, 같은 거리 성적, 기수 승률 …) JSON */
    @Lob
    private String factsJson;

    /** 전날 예상 근거: 요인마다 이 말을 올린(+)/내린(-) 정도 JSON {"form3":0.18,...} */
    @Lob
    private String preWhyJson;

    public String getFactsJson() { return factsJson; }
    public void setFactsJson(String factsJson) { this.factsJson = factsJson; }
    public String getPreWhyJson() { return preWhyJson; }
    public void setPreWhyJson(String preWhyJson) { this.preWhyJson = preWhyJson; }

    /** 전날 예상 우승 확률 */
    private double preP;

    /** 전날 예상 순위 */
    private int preRank;

    /** 확정 착순 (경주 전 null) */
    private Integer finishOrd;

    /** 확정 단승 배당 */
    private Double finalOdds;

    public Long getId() { return id; }
    public Race getRace() { return race; }
    public void setRace(Race race) { this.race = race; }
    public int getChulNo() { return chulNo; }
    public void setChulNo(int chulNo) { this.chulNo = chulNo; }
    public String getHrNo() { return hrNo; }
    public void setHrNo(String hrNo) { this.hrNo = hrNo; }
    public String getHrName() { return hrName; }
    public void setHrName(String hrName) { this.hrName = hrName; }
    public String getJkName() { return jkName; }
    public void setJkName(String jkName) { this.jkName = jkName; }
    public String getTrName() { return trName; }
    public void setTrName(String trName) { this.trName = trName; }
    public Double getDays() { return days; }
    public void setDays(Double days) { this.days = days; }
    public boolean isFirstStart() { return firstStart; }
    public void setFirstStart(boolean firstStart) { this.firstStart = firstStart; }
    public String getFeaturesJson() { return featuresJson; }
    public void setFeaturesJson(String featuresJson) { this.featuresJson = featuresJson; }
    public double getPreP() { return preP; }
    public void setPreP(double preP) { this.preP = preP; }
    public int getPreRank() { return preRank; }
    public void setPreRank(int preRank) { this.preRank = preRank; }
    public Integer getFinishOrd() { return finishOrd; }
    public void setFinishOrd(Integer finishOrd) { this.finishOrd = finishOrd; }
    public Double getFinalOdds() { return finalOdds; }
    public void setFinalOdds(Double finalOdds) { this.finalOdds = finalOdds; }
}
