package com.flexiple.ai_recruiter.model;

import java.util.List;

public record Profile(
    String id,
    String name,
    String current_title,
    Integer years_experience,
    String location,
    String current_company,
    String current_company_type,
    List<String> skills,
    List<PastCompany> past_companies,
    String education,
    String summary
) {}
