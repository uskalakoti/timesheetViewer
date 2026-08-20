package com.timesheet.validator.repository;

import com.timesheet.validator.domain.SowPo;
import com.timesheet.validator.domain.SowPoId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SowPoRepository extends JpaRepository<SowPo, SowPoId> {

    List<SowPo> findBySowNumber(String sowNumber);

}
