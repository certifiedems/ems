package com.ems.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ems.dto.response.ProctorEvidenceResponse;
import com.ems.entity.ExamSession;
import com.ems.entity.ProctorEvidence;
import com.ems.entity.Violation;

public interface ProctorEvidenceRepository extends JpaRepository<ProctorEvidence, Long> {

    Optional<ProctorEvidence> findByViolation(Violation violation);

    boolean existsByViolation(Violation violation);

    List<ProctorEvidence> findByExamSessionOrderByCapturedAtDesc(ExamSession examSession);

    long countByExamSession(ExamSession examSession);

    /**
     * Metadata-only projection for invigilator timelines. Deliberately excludes
     * {@code evidence_payload} so listing a session's evidence never streams
     * megabytes of base64 out of the database.
     */
    @Query("""
            SELECT new com.ems.dto.response.ProctorEvidenceResponse(
                       e.id, e.violation.id, e.storageKind, e.mediaType,
                       e.payloadBytes, e.frameWidth, e.frameHeight, e.capturedAt)
            FROM ProctorEvidence e
            WHERE e.examSession.id = :sessionId
            ORDER BY e.capturedAt DESC
            """)
    List<ProctorEvidenceResponse> findEvidenceMetadataBySession(@Param("sessionId") Long sessionId);

    /** Total bytes of evidence retained for a session, used for retention/quota checks. */
    @Query("SELECT COALESCE(SUM(e.payloadBytes), 0) FROM ProctorEvidence e WHERE e.examSession.id = :sessionId")
    long sumPayloadBytesBySession(@Param("sessionId") Long sessionId);
}
