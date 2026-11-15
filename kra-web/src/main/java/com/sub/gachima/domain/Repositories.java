package com.sub.gachima.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 저장소 인터페이스 모음 */
public final class Repositories {
    private Repositories() {}

    public interface MeetDayRepository extends JpaRepository<MeetDay, Long> {
        Optional<MeetDay> findByRaceDateAndMeet(String raceDate, int meet);
        List<MeetDay> findAllByOrderByRaceDateDescMeetAsc();
        List<MeetDay> findByRaceDateOrderByMeet(String raceDate);
    }

    public interface RaceRepository extends JpaRepository<Race, Long> {
        Optional<Race> findByMeetDayAndRcNo(MeetDay meetDay, int rcNo);
        List<Race> findByMeetDayOrderByRcNo(MeetDay meetDay);
    }

    public interface DayInputRepository extends JpaRepository<DayInput, Long> {
        Optional<DayInput> findFirstByRaceOrderByCreatedAtDescIdDesc(Race race);
    }
}
