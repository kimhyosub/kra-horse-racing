package com.sub.gachima.domain;

import javax.persistence.*;

/** 경주일 하루의 한 경마장. 당일 모델 가중치와 요인 목록을 함께 보관한다. */
@Entity
@Table(name = "meet_day", uniqueConstraints = @UniqueConstraint(columnNames = {"race_date", "meet"}))
public class MeetDay {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** yyyyMMdd */
    @Column(name = "race_date", nullable = false, length = 8)
    private String raceDate;

    /** 1=서울 2=제주 3=부경 4=영천 */
    private int meet;

    @Column(length = 10)
    private String meetName;

    /** 당일 모델 요인 이름 목록 (JSON 배열) */
    @Lob
    private String featsJson;

    /** 표준화할 때 평균만 빼는 이진 요인 (JSON 배열) */
    @Lob
    private String binJson;

    /** 당일에 정해지는 요인: 마체중·주로 (JSON 배열) */
    @Lob
    private String dayOnlyJson;

    /** 당일 모델 가중치 [시장 로그확률 계수, 요인 계수...] (JSON 배열) */
    @Lob
    private String dayWeightsJson;

    public Long getId() { return id; }
    public String getRaceDate() { return raceDate; }
    public void setRaceDate(String raceDate) { this.raceDate = raceDate; }
    public int getMeet() { return meet; }
    public void setMeet(int meet) { this.meet = meet; }
    public String getMeetName() { return meetName; }
    public void setMeetName(String meetName) { this.meetName = meetName; }
    public String getFeatsJson() { return featsJson; }
    public void setFeatsJson(String featsJson) { this.featsJson = featsJson; }
    public String getBinJson() { return binJson; }
    public void setBinJson(String binJson) { this.binJson = binJson; }
    public String getDayOnlyJson() { return dayOnlyJson; }
    public void setDayOnlyJson(String dayOnlyJson) { this.dayOnlyJson = dayOnlyJson; }
    public String getDayWeightsJson() { return dayWeightsJson; }
    public void setDayWeightsJson(String dayWeightsJson) { this.dayWeightsJson = dayWeightsJson; }
}
