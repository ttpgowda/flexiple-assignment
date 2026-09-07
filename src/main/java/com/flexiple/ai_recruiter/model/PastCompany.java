package com.flexiple.ai_recruiter.model;

public record PastCompany(
    String company,
    String company_type,
    String title,
    Integer years
) {}
