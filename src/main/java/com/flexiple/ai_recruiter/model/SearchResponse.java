package com.flexiple.ai_recruiter.model;

import java.util.List;

public record SearchResponse(
    int matchedCount,
    List<ScoredProfile> profiles
) {}
