package com.ems.service;

import java.util.List;

import com.ems.dto.response.AdminExamBookingDetailResponse;
import com.ems.dto.response.AdminExamBookingResponse;

/**
 * The admin exam tracker: every paid or booked application, followed from its
 * slot through the attempt to the result.
 */
public interface AdminExamTrackerService {

    /** Every paid or booked application, soonest slot first; those with no slot yet come last. */
    List<AdminExamBookingResponse> listBookings();

    /** One application end to end, including each question its attempt drew and the verdict on its answer. */
    AdminExamBookingDetailResponse getBooking(Long applicationId);
}
