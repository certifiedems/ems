package com.ems.service;

import java.util.List;

import com.ems.dto.request.ExamDurationUpdateRequest;
import com.ems.dto.request.ExamPassingMarksUpdateRequest;
import com.ems.dto.request.ExamScheduleRequest;
import com.ems.dto.request.ExamUpsertRequest;
import com.ems.dto.response.ExamBookingWindowResponse;
import com.ems.dto.response.ExamResponse;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;

public interface ExamService {

    ExamResponse create(ExamUpsertRequest request);

    ExamResponse update(Long examId, ExamUpsertRequest request);

    void delete(Long examId);

    ExamResponse publish(Long examId);

    ExamResponse schedule(Long examId, ExamScheduleRequest request);

    ExamResponse updateDuration(Long examId, ExamDurationUpdateRequest request);

    ExamResponse updatePassingMarks(Long examId, ExamPassingMarksUpdateRequest request);

    List<ExamResponse> search(String examCode, String examName, CertificationLevel certificationLevel,
            ExamStatus examStatus, Boolean published);

    /**
     * Every exam's booking window, with the number of paid candidates waiting on
     * it. Backs the admin screen that reopens a window that has run out.
     */
    List<ExamBookingWindowResponse> getBookingWindows();
}
