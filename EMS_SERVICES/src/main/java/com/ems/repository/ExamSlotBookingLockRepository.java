package com.ems.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ems.entity.ExamSlotBookingLock;

import jakarta.persistence.LockModeType;

public interface ExamSlotBookingLockRepository extends JpaRepository<ExamSlotBookingLock, Integer> {

    /**
     * Takes the booking lock under {@code SELECT ... FOR UPDATE}, waiting for any
     * booking that already holds it. Released when the transaction ends.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ExamSlotBookingLock l WHERE l.id = :id")
    Optional<ExamSlotBookingLock> findByIdForUpdate(@Param("id") Integer id);
}
