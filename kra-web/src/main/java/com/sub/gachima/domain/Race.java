package com.sub.gachima.domain;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "race", uniqueConstraints = @UniqueConstraint(columnNames = {"meet_day_id", "rc_no"}))
public class Race {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "meet_day_id")
    private MeetDay meetDay;

    @Column(name = "rc_no")
    private int rcNo;

    @Column(length = 10)
    private String stTime;

    private int dist;

    @OneToMany(mappedBy = "race", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("chulNo")
    private List<Entry> entries = new ArrayList<>();

    public Long getId() { return id; }
    public MeetDay getMeetDay() { return meetDay; }
    public void setMeetDay(MeetDay meetDay) { this.meetDay = meetDay; }
    public int getRcNo() { return rcNo; }
    public void setRcNo(int rcNo) { this.rcNo = rcNo; }
    public String getStTime() { return stTime; }
    public void setStTime(String stTime) { this.stTime = stTime; }
    public int getDist() { return dist; }
    public void setDist(int dist) { this.dist = dist; }
    public List<Entry> getEntries() { return entries; }
}
