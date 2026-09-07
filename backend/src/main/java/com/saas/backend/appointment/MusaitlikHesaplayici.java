package com.saas.backend.appointment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure interval arithmetic. All intervals are half-open [start, end). */
public final class MusaitlikHesaplayici {
    public record ZamanAraligi(Instant startsAt, Instant endsAt) {
        public ZamanAraligi {
            if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt))
                throw new IllegalArgumentException("Invalid interval");
        }
    }

    public List<ZamanAraligi> hesapla(List<ZamanAraligi> hours, List<ZamanAraligi> leave,
                                    List<ZamanAraligi> appointments, int duration, int buffer) {
        if (duration <= 0 || buffer < 0) throw new IllegalArgumentException("Invalid duration");
        long seconds = Math.multiplyExact((long) duration + buffer, 60);
        var blocked = new ArrayList<>(leave);
        blocked.addAll(appointments);
        var free = new ArrayList<>(merge(hours));
        for (var obstacle : merge(blocked)) {
            var next = new ArrayList<ZamanAraligi>();
            for (var interval : free) {
                if (!obstacle.startsAt().isBefore(interval.endsAt()) || !interval.startsAt().isBefore(obstacle.endsAt())) {
                    next.add(interval); continue;
                }
                if (interval.startsAt().isBefore(obstacle.startsAt()))
                    next.add(new ZamanAraligi(interval.startsAt(), obstacle.startsAt()));
                if (obstacle.endsAt().isBefore(interval.endsAt()))
                    next.add(new ZamanAraligi(obstacle.endsAt(), interval.endsAt()));
            }
            free = next;
        }
        var slots = new ArrayList<ZamanAraligi>();
        for (var gap : free)
            for (var start = gap.startsAt(); !start.plusSeconds(seconds).isAfter(gap.endsAt()); start = start.plusSeconds(seconds))
                slots.add(new ZamanAraligi(start, start.plusSeconds(duration * 60L)));
        return List.copyOf(slots);
    }

    private List<ZamanAraligi> merge(List<ZamanAraligi> input) {
        var result = new ArrayList<ZamanAraligi>();
        for (var row : input.stream().sorted(Comparator.comparing(ZamanAraligi::startsAt)).toList()) {
            if (result.isEmpty() || row.startsAt().isAfter(result.getLast().endsAt())) result.add(row);
            else {
                var last = result.removeLast();
                result.add(new ZamanAraligi(last.startsAt(), last.endsAt().isAfter(row.endsAt()) ? last.endsAt() : row.endsAt()));
            }
        }
        return result;
    }
}

