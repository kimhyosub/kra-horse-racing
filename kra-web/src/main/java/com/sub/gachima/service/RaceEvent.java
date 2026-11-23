package com.sub.gachima.service;

/** 경주에 당일 입력(출발 전 ★ 기록)이나 결과가 들어왔다는 알림. 텔레그램 공개 채널이 받는다. */
public class RaceEvent {
    public enum Kind { INPUT, RESULT }

    public final Kind kind;
    public final String date;
    public final int meet, rcNo;

    public RaceEvent(Kind kind, String date, int meet, int rcNo) {
        this.kind = kind; this.date = date; this.meet = meet; this.rcNo = rcNo;
    }

    @Override public String toString() { return kind + " " + date + "-" + meet + "-" + rcNo; }
}
