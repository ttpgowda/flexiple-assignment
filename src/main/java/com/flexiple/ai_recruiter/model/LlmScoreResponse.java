package com.flexiple.ai_recruiter.model;

public record LlmScoreResponse(
    Integer score,
    String explanation
) {}
