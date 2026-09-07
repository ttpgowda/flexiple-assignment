package com.flexiple.ai_recruiter.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefineResponse(
    @JsonProperty("objective_filters") ObjectiveFilters objectiveFilters,
    @JsonProperty("subjective_rubric") SubjectiveRubric subjectiveRubric,
    String explanation
) {}
