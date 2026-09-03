package com.ems.service;

/**
 * One proctoring frame resolved back to bytes, whichever side of the
 * {@code storage_kind} branch it came from.
 */
public record ProctorEvidenceContent(byte[] bytes, String mediaType, String storageKey) {
}
