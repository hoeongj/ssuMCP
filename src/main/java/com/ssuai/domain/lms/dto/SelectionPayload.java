package com.ssuai.domain.lms.dto;

import java.util.List;

/**
 * Persisted export selection. {@code totalBytes} carries the estimated size computed at
 * prepare time (sum of known material sizes) so {@code confirm} can surface it without a re-fetch.
 */
public record SelectionPayload(List<LmsExportSelectionItem> selections, long totalBytes) {}
