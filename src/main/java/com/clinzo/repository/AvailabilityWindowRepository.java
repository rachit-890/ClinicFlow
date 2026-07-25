package com.clinzo.repository;

import com.clinzo.domain.AvailabilityWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AvailabilityWindowRepository extends JpaRepository<AvailabilityWindow, Long> {

    List<AvailabilityWindow> findByDoctorIdAndActiveTrue(Long doctorId);
}
