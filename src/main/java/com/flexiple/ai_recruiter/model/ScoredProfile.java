package com.flexiple.ai_recruiter.model;

public record ScoredProfile(
    Profile profile,
    Integer score,
    String explanation
) {}
