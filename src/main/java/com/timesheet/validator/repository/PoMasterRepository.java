package com.timesheet.validator.repository;

import com.timesheet.validator.domain.PoMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PoMasterRepository extends JpaRepository<PoMaster, Long> {

    Optional<PoMaster> findByPoNumber(String poNumber);

}
