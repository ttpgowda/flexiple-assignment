package com.flexiple.ai_recruiter.model;

import java.util.List;

public record ObjectiveFilters(
    List<String> skills,
    Integer minYearsExperience,
    Integer maxYearsExperience,
    String location,
    List<String> companyTypes
) {}
