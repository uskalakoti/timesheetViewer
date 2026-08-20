//package com.timesheet.validator.repository;
//
//import com.timesheet.validator.domain.SowMaster;
//import org.springframework.data.jpa.repository.JpaRepository;
//import java.util.List;
//import java.util.Optional;
//
//public interface SowMasterRepository extends JpaRepository<SowMaster, Long> {
//    Optional<SowMaster> findBySowNumber(String sowNumber);
//    boolean existsBySowNumber(String sowNumber);
//    List<SowMaster> findByActiveTrue();
//}


package com.timesheet.validator.repository;

import com.timesheet.validator.domain.SowMaster;
import com.timesheet.validator.dto.SowMasterViewDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SowMasterRepository extends JpaRepository<SowMaster, Long> {

    Optional<SowMaster> findBySowNumber(String sowNumber);

    boolean existsBySowNumber(String sowNumber);

    List<SowMaster> findByActiveTrue();

    @Query("""
        SELECT new com.timesheet.validator.dto.SowMasterViewDto(

            sm.id,
            sm.sowNumber,
            sm.project,
            sm.projectLocation,
            sm.description,

            sm.startDate,
            sm.endDate,

            pm.poNumber,
            pm.updatedPoNumber,
            pm.startDate,
            pm.endDate,
            pm.poValue,

            sm.active
        )

        FROM SowMaster sm

        LEFT JOIN SowPo sp
            ON sm.sowNumber = sp.sowNumber

        LEFT JOIN PoMaster pm
            ON sp.poNumber = pm.poNumber

        ORDER BY sm.sowNumber
        """)
    List<SowMasterViewDto> findSowMasterView();
}
