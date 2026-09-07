package com.flexiple.ai_recruiter.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexiple.ai_recruiter.model.GenerateResponse;
import com.flexiple.ai_recruiter.model.RefineResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Service
public class GeminiService {

  private final String apiKey;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  @Value("classpath:prompts/generate_prompt.md")
  private Resource generatePromptResource;

  @Value("classpath:prompts/score_prompt.md")
  private Resource scorePromptResource;

  @Value("classpath:prompts/refine_prompt.md")
  private Resource refinePromptResource;

  private final String modelName;

  public GeminiService(@Value("${GEMINI_API_KEY:}") String apiKey,
      @Value("${GEMINI_MODEL:gemini-3.1-flash-lite}") String modelName) {
    this.apiKey = apiKey;
    this.modelName = modelName;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    this.objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
  }

  private String readPrompt(Resource resource) throws IOException {
    return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  public GenerateResponse generateCriteria(String query) throws Exception {
    String promptTemplate = readPrompt(generatePromptResource);
    String prompt = promptTemplate.replace("{query}", query);

    String schemaJson = """
        {
          "type": "OBJECT",
          "properties": {
            "objective_filters": {
              "type": "OBJECT",
              "properties": {
                "skills": { "type": "ARRAY", "items": { "type": "STRING" } },
                "minYearsExperience": { "type": "INTEGER" },
                "maxYearsExperience": { "type": "INTEGER", "nullable": true },
                "location": { "type": "STRING", "nullable": true },
                "companyTypes": { "type": "ARRAY", "items": { "type": "STRING" } }
              },
              "required": ["skills", "minYearsExperience"]
            },
            "subjective_rubric": {
              "type": "OBJECT",
              "properties": {
                "guidelines": { "type": "ARRAY", "items": { "type": "STRING" } }
              },
              "required": ["guidelines"]
            }
          },
          "required": ["objective_filters", "subjective_rubric"]
        }
        """;

    String result = callGemini(prompt, schemaJson);
    return objectMapper.readValue(result, GenerateResponse.class);
  }

  public List<com.flexiple.ai_recruiter.model.LlmScoreItem> scoreProfiles(String rubricJson, String profilesJson)
      throws Exception {
    String promptTemplate = readPrompt(scorePromptResource);
    String prompt = promptTemplate
        .replace("{rubric}", rubricJson)
        .replace("{profiles}", profilesJson);

    String schemaJson = """
        {
          "type": "ARRAY",
          "items": {
            "type": "OBJECT",
            "properties": {
              "id": { "type": "STRING" },
              "score": { "type": "INTEGER" },
              "explanation": { "type": "STRING" }
            },
            "required": ["id", "score", "explanation"]
          }
        }
        """;

    String result = callGemini(prompt, schemaJson);
    return objectMapper.readValue(result,
        new com.fasterxml.jackson.core.type.TypeReference<List<com.flexiple.ai_recruiter.model.LlmScoreItem>>() {
        });
  }

  public RefineResponse refineCriteria(String currentFiltersJson, String currentRubricJson, String shownProfilesJson,
      String chatHistoryJson) throws Exception {
    String promptTemplate = readPrompt(refinePromptResource);
    String prompt = promptTemplate
        .replace("{current_filters}", currentFiltersJson)
        .replace("{current_rubric}", currentRubricJson)
        .replace("{shown_profiles}", shownProfilesJson)
        .replace("{chat_history}", chatHistoryJson);

    String schemaJson = """
        {
          "type": "OBJECT",
          "properties": {
            "objective_filters": {
              "type": "OBJECT",
              "properties": {
                "skills": { "type": "ARRAY", "items": { "type": "STRING" } },
                "minYearsExperience": { "type": "INTEGER" },
                "maxYearsExperience": { "type": "INTEGER", "nullable": true },
                "location": { "type": "STRING", "nullable": true },
                "companyTypes": { "type": "ARRAY", "items": { "type": "STRING" } }
              },
              "required": ["skills", "minYearsExperience"]
            },
            "subjective_rubric": {
              "type": "OBJECT",
              "properties": {
                "guidelines": { "type": "ARRAY", "items": { "type": "STRING" } }
              },
              "required": ["guidelines"]
            },
            "explanation": { "type": "STRING" }
          },
          "required": ["objective_filters", "subjective_rubric", "explanation"]
        }
        """;

    String result = callGemini(prompt, schemaJson);
    return objectMapper.readValue(result, RefineResponse.class);
  }

  private String callGemini(String prompt, String schemaJson) throws Exception {
    String url = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent";

    // Escape prompt for JSON
    String escapedPrompt = objectMapper.writeValueAsString(prompt);

    String requestBody = """
        {
          "contents": [{
            "parts": [{"text": %s}]
          }],
          "generationConfig": {
            "responseMimeType": "application/json",
            "responseSchema": %s
          }
        }
        """.formatted(escapedPrompt, schemaJson);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .header("X-goog-api-key", apiKey)
        .timeout(Duration.ofSeconds(120))
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();

    int maxRetries = 2;
    int attempts = 0;
    while (attempts <= maxRetries) {
      try {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          JsonNode rootNode = objectMapper.readTree(response.body());
          return rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        } else if (response.statusCode() == 429 || response.statusCode() >= 500) {
          attempts++;
          if (attempts > maxRetries) {
            throw new RuntimeException(
                "Gemini API error after retries: " + response.statusCode() + " " + response.body());
          }
          Thread.sleep(2000 * attempts); // Exponential backoff
        } else {
          throw new RuntimeException("Gemini API error: " + response.statusCode() + " " + response.body());
        }
      } catch (IOException | InterruptedException e) {
        attempts++;
        if (attempts > maxRetries) {
          throw e;
        }
        Thread.sleep(2000 * attempts);
      }
    }
    throw new RuntimeException("Failed to call Gemini API");
  }
}
