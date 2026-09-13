package com.ems.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ems.entity.Question;
import com.ems.enums.CertificationLevel;
import com.ems.enums.QuestionSeverity;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    Optional<Question> findByQuestionCodeIgnoreCase(String questionCode);

    boolean existsByQuestionCodeIgnoreCase(String questionCode);

    List<Question> findByCertificationLevelAndActiveTrue(CertificationLevel certificationLevel);

    List<Question> findByCertificationLevelAndSeverityInAndActiveTrue(
            CertificationLevel certificationLevel,
            java.util.Collection<QuestionSeverity> severities);

    long countByCertificationLevelAndSeverityAndActiveTrue(
            CertificationLevel certificationLevel,
            QuestionSeverity severity);

    @Query("""
            select q from Question q
            where (:questionCode is null or lower(q.questionCode) like :questionCode)
            and (:certificationLevel is null or q.certificationLevel = :certificationLevel)
            and (:severity is null or q.severity = :severity)
            and (:active is null or q.active = :active)
            and (:searchText is null or lower(q.questionText) like :searchText)
            order by q.questionCode asc
            """)
    List<Question> search(
            @Param("questionCode") String questionCode,
            @Param("certificationLevel") CertificationLevel certificationLevel,
            @Param("severity") QuestionSeverity severity,
            @Param("active") Boolean active,
            @Param("searchText") String searchText);
}
