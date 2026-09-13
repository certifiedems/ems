package com.ems.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ems.entity.CertificationAttemptPolicy;
import com.ems.enums.CertificationLevel;

public interface CertificationAttemptPolicyRepository extends JpaRepository<CertificationAttemptPolicy, Long> {

    Optional<CertificationAttemptPolicy> findByCertificationLevel(CertificationLevel certificationLevel);
}
