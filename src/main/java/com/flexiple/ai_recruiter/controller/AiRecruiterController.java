package com.flexiple.ai_recruiter.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexiple.ai_recruiter.model.*;
import com.flexiple.ai_recruiter.model.ApiRequests.*;
import com.flexiple.ai_recruiter.service.GeminiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AiRecruiterController {

    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;
    private List<Profile> allProfiles;

    @Value("classpath:profiles.json")
    private Resource profilesResource;

    public AiRecruiterController(GeminiService geminiService) {
        this.geminiService = geminiService;
        this.objectMapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    public void init() throws IOException {
        allProfiles = objectMapper.readValue(
            profilesResource.getInputStream(),
            new TypeReference<List<Profile>>() {}
        );
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest request) {
        try {
            GenerateResponse response = geminiService.generateCriteria(request.query());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "gemini_error", "message", e.getMessage()));
        }
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchRequest request) {
        try {
            ObjectiveFilters filters = request.objectiveFilters();
            
            // Local Filtering (ANY skill match logic)
            List<Profile> filteredProfiles = allProfiles.stream().filter(profile -> {
                // Min Years check
                if (filters.minYearsExperience() != null && profile.years_experience() != null) {
                    if (profile.years_experience() < filters.minYearsExperience()) return false;
                }
                
                // Max Years check
                if (filters.maxYearsExperience() != null && profile.years_experience() != null) {
                    if (profile.years_experience() > filters.maxYearsExperience()) return false;
                }

                // Location check (case insensitive substring)
                if (filters.location() != null && !filters.location().isBlank() && profile.location() != null) {
                    if (!profile.location().toLowerCase().contains(filters.location().toLowerCase())) return false;
                }

                // Company Type check (ANY match)
                if (filters.companyTypes() != null && !filters.companyTypes().isEmpty() && profile.current_company_type() != null) {
                    boolean typeMatches = filters.companyTypes().stream()
                        .anyMatch(type -> type.equalsIgnoreCase(profile.current_company_type()));
                    if (!typeMatches) return false;
                }

                // Skills check (ANY match)
                if (filters.skills() != null && !filters.skills().isEmpty() && profile.skills() != null) {
                    boolean hasSkill = false;
                    for (String reqSkill : filters.skills()) {
                        for (String userSkill : profile.skills()) {
                            if (userSkill.toLowerCase().contains(reqSkill.toLowerCase()) || reqSkill.toLowerCase().contains(userSkill.toLowerCase())) {
                                hasSkill = true;
                                break;
                            }
                        }
                        if (hasSkill) break;
                    }
                    if (!hasSkill) return false;
                }

                return true;
            }).toList();

            int matchedCount = filteredProfiles.size();

            // Limit to top 15 candidates before sending to LLM for scoring to save time/tokens, since we only need top 4-5
            List<Profile> profilesToScore = filteredProfiles.stream().limit(15).toList();

            // LLM Scoring (in parallel could be faster, but sequential is fine for a small batch, or we can send them sequentially)
            // To make it faster for the test, let's just score the top 10
            // Batch LLM Scoring
            String rubricJson = objectMapper.writeValueAsString(request.subjectiveRubric());
            String profilesJson = objectMapper.writeValueAsString(profilesToScore);
            
            List<LlmScoreItem> batchScores = geminiService.scoreProfiles(rubricJson, profilesJson);
            
            // Map scores back to profiles
            List<ScoredProfile> scoredProfiles = new ArrayList<>();
            Map<String, LlmScoreItem> scoreMap = batchScores.stream()
                .collect(Collectors.toMap(LlmScoreItem::id, item -> item));

            for (Profile p : profilesToScore) {
                LlmScoreItem scoreItem = scoreMap.get(p.id());
                if (scoreItem != null) {
                    scoredProfiles.add(new ScoredProfile(p, scoreItem.score(), scoreItem.explanation()));
                } else {
                    scoredProfiles.add(new ScoredProfile(p, 0, "No score generated by AI"));
                }
            }

            // Sort by score descending and take top 5
            List<ScoredProfile> topProfiles = scoredProfiles.stream()
                .sorted(Comparator.comparingInt(ScoredProfile::score).reversed())
                .limit(5)
                .toList();

            return ResponseEntity.ok(new SearchResponse(matchedCount, topProfiles));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "search_error", "message", e.getMessage()));
        }
    }

    @PostMapping("/refine")
    public ResponseEntity<?> refine(@RequestBody RefineRequest request) {
        try {
            String currentFiltersJson = objectMapper.writeValueAsString(request.objectiveFilters());
            String currentRubricJson = objectMapper.writeValueAsString(request.subjectiveRubric());
            String shownProfilesJson = objectMapper.writeValueAsString(request.shownProfiles());
            String chatHistoryJson = objectMapper.writeValueAsString(request.chatHistory());

            RefineResponse response = geminiService.refineCriteria(currentFiltersJson, currentRubricJson, shownProfilesJson, chatHistoryJson);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "refine_error", "message", e.getMessage()));
        }
    }

    @PostMapping("/freeze")
    public ResponseEntity<?> freeze(@RequestBody FreezeRequest request) {
        // In a real application, you might persist this to a database
        // For this assignment, we just validate and echo it back as a confirmed state.
        return ResponseEntity.ok(Map.of("status", "frozen", "final_state", request));
    }
}
