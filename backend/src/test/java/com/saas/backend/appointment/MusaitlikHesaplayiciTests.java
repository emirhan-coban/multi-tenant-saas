package com.saas.backend.appointment;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.saas.backend.appointment.MusaitlikHesaplayici.ZamanAraligi;

class MusaitlikHesaplayiciTests {
    final MusaitlikHesaplayici calc=new MusaitlikHesaplayici();
    Instant t(int min) { return Instant.parse("2030-01-01T00:00:00Z").plusSeconds(min*60L); }
    ZamanAraligi r(int start,int end) { return new ZamanAraligi(t(start),t(end)); }
    List<ZamanAraligi> run(List<ZamanAraligi> hours,List<ZamanAraligi> leave,List<ZamanAraligi> busy,int duration,int buffer) {
        return calc.hesapla(hours,leave,busy,duration,buffer);
    }
    List<ZamanAraligi> day(List<ZamanAraligi> leave,List<ZamanAraligi> busy) {
        return run(List.of(r(540,720)),leave,busy,30,0);
    }
    @Test void closedDay() { assertTrue(run(List.of(),List.of(),List.of(),30,0).isEmpty()); }
    @Test void fullDay() { assertEquals(6,day(List.of(),List.of()).size()); }
    @Test void entireDayOff() { assertTrue(day(List.of(r(0,1440)),List.of()).isEmpty()); }
    @Test void entireDayBooked() { assertTrue(day(List.of(),List.of(r(540,720))).isEmpty()); }
    @Test void middleBreak() { assertEquals(4,day(List.of(r(600,660)),List.of()).size()); }
    @Test void leaveBeforeOpening() { assertEquals(6,day(List.of(r(0,540)),List.of()).size()); }
    @Test void leaveAfterClosing() { assertEquals(6,day(List.of(r(720,800)),List.of()).size()); }
    @Test void clippedBeginning() { assertEquals(t(570),day(List.of(r(0,570)),List.of()).getFirst().startsAt()); }
    @Test void clippedEnd() { assertEquals(t(690),day(List.of(r(690,800)),List.of()).getLast().endsAt()); }
    @Test void exactFit() { assertEquals(List.of(r(540,570)),run(List.of(r(540,570)),List.of(),List.of(),30,0)); }
    @Test void tooLong() { assertTrue(run(List.of(r(540,569)),List.of(),List.of(),30,0).isEmpty()); }
    @Test void bufferMustFit() { assertTrue(run(List.of(r(540,570)),List.of(),List.of(),30,1).isEmpty()); }
    @Test void bufferAffectsNextStart() { var s=run(List.of(r(540,660)),List.of(),List.of(),30,10); assertEquals(3,s.size()); assertEquals(t(580),s.get(1).startsAt()); assertEquals(t(570),s.getFirst().endsAt()); }
    @Test void overlappingObstaclesMerged() { assertEquals(3,day(List.of(r(570,630)),List.of(r(600,660))).size()); }
    @Test void duplicateHoursDoNotDuplicateSlots() { assertEquals(6,run(List.of(r(540,720),r(540,720)),List.of(),List.of(),30,0).size()); }
    @Test void unsortedHoursSorted() { var s=run(List.of(r(660,720),r(540,600)),List.of(),List.of(),30,0); assertEquals(t(540),s.getFirst().startsAt()); assertEquals(4,s.size()); }
    @Test void adjacentHoursMerged() { assertEquals(1,run(List.of(r(540,555),r(555,570)),List.of(),List.of(),30,0).size()); }
    @Test void fragmentedGapsTooSmall() { assertTrue(run(List.of(r(540,600)),List.of(r(560,580)),List.of(),30,0).isEmpty()); }
    @Test void zeroDurationRejected() { assertThrows(IllegalArgumentException.class,()->run(List.of(),List.of(),List.of(),0,0)); }
    @Test void negativeBufferRejected() { assertThrows(IllegalArgumentException.class,()->run(List.of(),List.of(),List.of(),30,-1)); }
    @Test void invalidIntervalRejected() { assertThrows(IllegalArgumentException.class,()->r(600,540)); }
    @Test void inputsNotMutated() { var h=new ArrayList<>(List.of(r(540,720))); var copy=List.copyOf(h); run(h,List.of(),List.of(),30,0); assertEquals(copy,h); }
    @Test void utcAcrossMidnight() { assertEquals(4,run(List.of(r(1380,1500)),List.of(),List.of(),30,0).size()); }
}

