package com.saas.backend.calismasaati;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.personel.Personel;
import com.saas.backend.personel.PersonelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class CalismaSaatiService {
    private final CalismaSaatiRepository hours;
    private final PersonelRepository staff;

    public CalismaSaatiService(CalismaSaatiRepository hours, PersonelRepository staff) {
        this.hours = hours;
        this.staff = staff;
    }

    @Transactional
    public CalismaSaatiDtos.CalismaSaatiResponse olustur(
            AuthenticatedUser user, CalismaSaatiDtos.CalismaSaatiRequest request) {
        validateRange(request.startTime(), request.endTime());
        Personel person = lockStaff(user, request.personelId());
        if (hours.findByPersonelIdAndTenantIdOrderByDayOfWeek(person.getId(), user.tenantId()).stream()
                .anyMatch(row -> row.getDayOfWeek().equals(request.dayOfWeek()))) {
            throw new ApiException("DAY_ALREADY_EXISTS", "Bu gün için saat kaydı var. Haftalık düzenlemeyi kullanın.");
        }
        return response(hours.save(row(person, request.dayOfWeek(), request.startTime(), request.endTime())));
    }

    @Transactional
    public List<CalismaSaatiDtos.CalismaSaatiResponse> haftayiKaydet(
            AuthenticatedUser user, CalismaSaatiDtos.HaftalikRequest request) {
        // Validate the whole week before changing any existing rows.
        if (request.days() == null || request.days().size() != 7) invalidWeek();
        var seen = new HashSet<Integer>();
        for (var day : request.days()) {
            if (day == null || day.dayOfWeek() == null || day.dayOfWeek() < 0 || day.dayOfWeek() > 6
                    || !seen.add(day.dayOfWeek()) || day.closed() == null) invalidWeek();
            if (day.closed()) {
                if (day.startTime() != null || day.endTime() != null) {
                    throw new ApiException("INVALID_RANGE", "Kapalı günlerde saat girilmemeli");
                }
            } else validateRange(day.startTime(), day.endTime());
        }
        // Serialize POST/PUT for this staff member, including initially empty schedules.
        Personel person = lockStaff(user, request.personelId());
        var existing = hours.findByPersonelIdAndTenantIdOrderByDayOfWeek(person.getId(), user.tenantId());
        hours.deleteAll(existing);
        hours.flush();
        var replacement = request.days().stream().filter(day -> !day.closed())
                .sorted(Comparator.comparing(CalismaSaatiDtos.GunRequest::dayOfWeek))
                .map(day -> row(person, day.dayOfWeek(), day.startTime(), day.endTime())).toList();
        return hours.saveAllAndFlush(replacement).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public List<CalismaSaatiDtos.CalismaSaatiResponse> listele(AuthenticatedUser user, UUID personelId) {
        staff.findByIdAndTenantIdAndActiveTrue(personelId, user.tenantId())
                .orElseThrow(() -> new ApiException("NOT_FOUND", "Personel bulunamadı"));
        return hours.findByPersonelIdAndTenantIdOrderByDayOfWeek(personelId, user.tenantId())
                .stream().map(this::response).toList();
    }

    private Personel lockStaff(AuthenticatedUser user, UUID personelId) {
        return staff.findForScheduleUpdate(personelId, user.tenantId())
                .orElseThrow(() -> new ApiException("NOT_FOUND", "Personel bulunamadı"));
    }

    private static void invalidWeek() {
        throw new ApiException("INVALID_WEEK", "Haftanın yedi günü birer kez gönderilmeli");
    }

    private static void validateRange(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ApiException("INVALID_RANGE", "Başlangıç saati bitiş saatinden önce olmalı");
        }
    }

    private CalismaSaati row(Personel person, int day, LocalTime start, LocalTime end) {
        var row = new CalismaSaati();
        row.setTenant(person.getTenant()); row.setPersonel(person);
        row.setDayOfWeek(day); row.setStartTime(start); row.setEndTime(end);
        return row;
    }

    private CalismaSaatiDtos.CalismaSaatiResponse response(CalismaSaati row) {
        return new CalismaSaatiDtos.CalismaSaatiResponse(row.getId(), row.getPersonel().getId(),
                row.getDayOfWeek(), row.getStartTime(), row.getEndTime());
    }
}
