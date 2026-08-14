package com.timesheet.validator.repository;

import com.timesheet.validator.dto.ResourceMasterViewDto;
import com.timesheet.validator.domain.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ResourceMasterRepository extends JpaRepository<Resource, Long> {

    @Query("""
        SELECT new com.timesheet.validator.dto.ResourceMasterViewDto(
            r.id,
            r.resourceId,
            r.name,
            r.location,
            r.company,
            r.dailyRateUsd,
            r.workingHoursPerDay,
            r.startDate,
            r.endDate,

            rs.assignedTeam,
            sm.project,
            rs.subProject,
            rs.projectCode,
            rs.sowNumber,
            rs.roleInSow,
            rs.travelExpense,

            sm.description,
            sm.startDate,
            sm.endDate,

            pm.poNumber,
            pm.updatedPoNumber,
            pm.poValue,
            pm.startDate,
            pm.endDate
        )
        FROM Resource r

        LEFT JOIN ResourceSow rs
            ON r.resourceId = rs.resourceId

        LEFT JOIN SowMaster sm
            ON rs.sowNumber = sm.sowNumber

        LEFT JOIN SowPo sp
            ON rs.sowNumber = sp.sowNumber

        LEFT JOIN PoMaster pm
            ON sp.poNumber = pm.poNumber

        ORDER BY r.name
        """)
    List<ResourceMasterViewDto> findResourceMasterView();
}