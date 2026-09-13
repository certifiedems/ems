package com.ems.service;

import com.ems.dto.response.ViolationSummaryResponse;

public interface ExamHeartbeatService {

    /**
     * Notes that one copy of the exam page is alive, records the attempt as open
     * in two places when this heartbeat proves it, and returns where the session
     * stands afterwards.
     */
    ViolationSummaryResponse heartbeat(String email, Long sessionId, String clientId);
}
