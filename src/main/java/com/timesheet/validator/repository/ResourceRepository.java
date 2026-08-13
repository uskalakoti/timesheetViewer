package com.timesheet.validator.repository;

import com.timesheet.validator.domain.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    Optional<Resource> findByName(String name);

    Optional<Resource> findByResourceId(String resourceId);

    boolean existsByResourceId(String resourceId);


    /*
     * ============================================================
     * RESOURCE MASTER JOIN
     * ============================================================
     *
     * One result row represents:
     *
     * Employee + SOW + PO
     *
     * RESOURCE
     *      |
     *      | RESOURCE_ID
     *      v
     * RESOURCE_SOW
     *      |
     *      | SOW_NUMBER
     *      v
     * SOW_MASTER
     *
     * RESOURCE_SOW
     *      |
     *      | SOW_NUMBER
     *      v
     * SOW_PO
     *      |
     *      | PO_NUMBER
     *      v
     * PO_MASTER
     *
     * LEFT JOIN is used for SOW/PO information so that a resource
     * can still appear even if its SOW/PO mapping is incomplete.
     */
    @Query(value = """
            SELECT
                r.RESOURCE_ID              AS employeeId,
                r.NAME                     AS employeeName,
                r.LOCATION                 AS employeeLocation,
                r.COMPANY                  AS company,
                r.DAILY_RATE_USD           AS dailyRateUsd,
                r.WORKING_HOURS_PER_DAY    AS workingHoursPerDay,
                r.START_DATE               AS startDate,
                r.END_DATE                 AS endDate,

                rs.ASSIGNED_TEAM           AS assignedTeam,
                rs.SUB_PROJECT             AS subProject,
                rs.PROJECT_CODE            AS projectCode,
                rs.TRAVEL_EXPENSE          AS travelExpense,
                rs.SOW_NUMBER              AS sowNumber,
                rs.ROLE_IN_SOW             AS roleInSow,

                sm.PROJECT                 AS project,
                sm.PROJECT_LOCATION       AS projectLocation,
                sm.DESCRIPTION             AS sowDescription,

                pm.PO_NUMBER               AS poNumber,
                pm.UPDATED_PO_NUMBER      AS updatedPoNumber,
                pm.PO_VALUE               AS poValue,
                pm.START_DATE             AS poStartDate,
                pm.END_DATE               AS poEndDate

            FROM RESOURCE r

            LEFT JOIN RESOURCE_SOW rs
                ON r.RESOURCE_ID = rs.RESOURCE_ID

            LEFT JOIN SOW_MASTER sm
                ON rs.SOW_NUMBER = sm.SOW_NUMBER

            LEFT JOIN SOW_PO sp
                ON rs.SOW_NUMBER = sp.SOW_NUMBER

            LEFT JOIN PO_MASTER pm
                ON sp.PO_NUMBER = pm.PO_NUMBER

            ORDER BY r.NAME, rs.SOW_NUMBER, pm.PO_NUMBER
            """, nativeQuery = true)
    List<ResourceMasterProjection> findResourceMasterData();
}