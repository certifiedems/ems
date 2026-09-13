package com.ems.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ems.entity.ProctoringPolicy;

public interface ProctoringPolicyRepository extends JpaRepository<ProctoringPolicy, Long> {

    Optional<ProctoringPolicy> findByScopeKey(String scopeKey);

    /** Ids of the exams that run under rules of their own rather than the default. */
    @Query("select p.exam.id from ProctoringPolicy p where p.exam is not null")
    List<Long> findExamIdsWithOwnPolicy();
}
