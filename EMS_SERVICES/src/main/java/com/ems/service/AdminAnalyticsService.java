package com.ems.service;

import java.time.ZoneId;

import com.ems.dto.response.AdminAnalyticsResponse;

/** The board figures behind the admin overview. */
public interface AdminAnalyticsService {

    /**
     * All-time headline figures, plus the last {@code months} calendar months of
     * trends bucketed in {@code zone}. {@code months} is clamped to 1..36.
     */
    AdminAnalyticsResponse getBoardAnalytics(int months, ZoneId zone);
}
