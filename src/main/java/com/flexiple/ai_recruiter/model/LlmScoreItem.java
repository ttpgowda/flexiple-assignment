package com.flexiple.ai_recruiter.model;

public record LlmScoreItem(
    String id,
    Integer score,
    String explanation
) {}
