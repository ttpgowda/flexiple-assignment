package com.flexiple.ai_recruiter.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ApiRequests {

    public record GenerateRequest(String query) {}

    public record SearchRequest(
        @JsonProperty("objective_filters") ObjectiveFilters objectiveFilters,
        @JsonProperty("subjective_rubric") SubjectiveRubric subjectiveRubric
    ) {}

    public record ChatMessage(String role, String message) {}

    public record RefineRequest(
        @JsonProperty("objective_filters") ObjectiveFilters objectiveFilters,
        @JsonProperty("subjective_rubric") SubjectiveRubric subjectiveRubric,
        @JsonProperty("shown_profiles") List<Profile> shownProfiles,
        @JsonProperty("chat_history") List<ChatMessage> chatHistory
    ) {}

    public record FreezeRequest(
        @JsonProperty("objective_filters") ObjectiveFilters objectiveFilters,
        @JsonProperty("subjective_rubric") SubjectiveRubric subjectiveRubric,
        List<ScoredProfile> profiles
    ) {}

}
