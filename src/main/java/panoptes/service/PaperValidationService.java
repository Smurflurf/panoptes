package panoptes.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import panoptes.dto.DetailedResult;
import panoptes.dto.ValidatedResult;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An epistemic validation service that grounds semantic search results in hard academic metadata.
 * <p>
 * Before a paper from the Ideenatlas can be used by the AI agents, this service queries the 
 * <a href="https://openalex.org/">OpenAlex API</a> to retrieve its empirical metadata (citation count, 
 * publication year, peer-review status, and retraction status).
 * </p>
 * <p>
 * To prevent the LLM from hallucinating connections, this service implements strict string-matching 
 * and Jaccard similarity checks to ensure the returned OpenAlex metadata matches the original paper 
 * precisely. If a paper is retracted or unverified, it is either flagged or discarded.
 * </p>
 */
@Service
public class PaperValidationService {

    private static final Logger log = LoggerFactory.getLogger(PaperValidationService.class);

    private final RestClient restClient;
    private final Map<String, ValidatedResult> validationCache = new ConcurrentHashMap<>();
    private final List<String> apiKeys;
    private final AtomicInteger keyCounter = new AtomicInteger(0);

    public PaperValidationService(
            @Value("${openalex.api-keys:}") List<String> apiKeys,
            @Value("${openalex.email:}") String email) {
        this.apiKeys = apiKeys;
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openalex.org");
        if (email != null && !email.isBlank()) builder.defaultHeader("User-Agent", "mailto:" + email);
        this.restClient = builder.build();
    }

    public ValidatedResult validatePaper(DetailedResult result) {
        if (validationCache.containsKey(result.id())) return validationCache.get(result.id());

        String rawTitle = result.title();
        String queryFull = rawTitle.replaceAll("[^\\p{L}\\p{N}\\- ]", "").trim();
        
        String queryShort;
        if (rawTitle.contains(":")) {
            queryShort = rawTitle.substring(0, rawTitle.indexOf(":")).replaceAll("[^\\p{L}\\p{N}\\- ]", "").trim();
        } else {
            String[] words = queryFull.split("\\s+");
            queryShort = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 6)));
        }

		int maxRetries = Math.max(4, apiKeys.isEmpty() ? 4 : apiKeys.size() * 2);

		for (int attempt = 0; attempt < maxRetries; attempt++) {
			String currentKey = apiKeys.isEmpty() ? null : apiKeys.get(keyCounter.getAndIncrement() % apiKeys.size());

			try {
				// STAGE 1: Full Query
				JsonNode response = fetch(queryFull, currentKey);

				// STAGE 2: Waterfall-Fallback
				if (response == null || !response.has("results") || response.path("results").isEmpty()) {
					response = fetch(queryShort, currentKey);
				}

				if (response != null && response.has("results") && !response.path("results").isEmpty()) {
					JsonNode resultsArray = response.path("results");
					JsonNode bestMatch = null;

					for (int j = 0; j < Math.min(resultsArray.size(), 25); j++) {
						JsonNode candidate = resultsArray.get(j);
						String candidateTitle = candidate.path("title").asText("");

						if (isStrictTitleMatch(rawTitle, candidateTitle)) {
							bestMatch = candidate;
							String type = candidate.path("type").asText("");
							String candidateDoi = candidate.path("doi").asText(null);

							boolean isPeer = type.equals("article") || type.equals("review")
									|| type.equals("conference-paper") || type.equals("data-paper")
									|| type.equals("software-paper");

							// Nature News doesn't show real papers.
							// I should add more things here...
							if (candidateDoi != null
									&& (candidateDoi.contains("d41586") || candidateDoi.contains("10.1038/d41586"))) {
								isPeer = false;
							}

							if (isPeer) {
								break; // Perfect match
							}
						}
					}

					if (bestMatch != null) {
						String doi = bestMatch.path("doi").asText(null);
						int citations = bestMatch.path("cited_by_count").asInt(0);
						boolean isRetracted = bestMatch.path("is_retracted").asBoolean(false);
						Integer year = bestMatch.hasNonNull("publication_year")
								? bestMatch.path("publication_year").asInt()
								: null;
						String type = bestMatch.path("type").asText("");

						boolean isPeerReviewed = type.equals("article") || type.equals("review")
								|| type.equals("conference-paper") || type.equals("data-paper")
								|| type.equals("software-paper");

						// Nature News doesn't show real papers.
						// I should add more things here...
						if (doi != null && (doi.contains("d41586") || doi.contains("10.1038/d41586"))) {
							isPeerReviewed = false;
						}

						ValidatedResult validResult = new ValidatedResult(result, doi, isPeerReviewed, citations,
								isRetracted, year);
						validationCache.put(result.id(), validResult);
						Thread.sleep(100);
						return validResult;
					}
				}
				break; // No matches? No problem. End it

			} catch (Exception e) {
				String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();

				// 400 Bad Request (special characters, no retry) 
				if (errorMsg.contains("400")) {
					log.warn("OpenAlex 400 Bad Request. Unsupported characters in title: '{}'", queryFull);
					break;
				}

				log.warn("OpenAlex API Error ({}/{}): '{}' - Retrying...", attempt + 1, maxRetries, errorMsg);

				try {
					Thread.sleep(500L * (attempt + 1));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
		}

        // BESSER KEINE METADATEN ALS FALSCHE! 
		// PREFER NO METADATA OVER WRONG METADATA.
        ValidatedResult fallback = new ValidatedResult(result, null, false, 0, false, null);
        validationCache.put(result.id(), fallback);
        return fallback;
    }

    private boolean isStrictTitleMatch(String t1, String t2) {
        if (t1 == null || t2 == null) return false;
        
        // 1. Check for direct match
        String norm1 = t1.toLowerCase().replaceAll("[^a-z0-9]", "");
        String norm2 = t2.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (norm1.equals(norm2)) return true;

        // 2. Token-based Jaccard-Matching
        Set<String> set1 = new HashSet<>(Arrays.asList(t1.toLowerCase().replaceAll("[^a-z0-9 ]", "").split("\\s+")));
        Set<String> set2 = new HashSet<>(Arrays.asList(t2.toLowerCase().replaceAll("[^a-z0-9 ]", "").split("\\s+")));
        
        Set<String> stopWords = Set.of("a", "an", "the", "in", "on", "of", "and", "or", "to", "for", "with");
        set1.removeAll(stopWords);
        set2.removeAll(stopWords);
        
        if (set1.isEmpty() || set2.isEmpty()) return false;
        
        int overlap = 0;
        for (String w : set1) {
            if (set2.contains(w)) overlap++;
        }
        
        double matchScore = (double) overlap / Math.max(set1.size(), set2.size());
        return matchScore >= 0.85; // Need 85% overlap 
    }

    private JsonNode fetch(String query, String currentKey) throws Exception {
        String encodedTitle = URLEncoder.encode(query, StandardCharsets.UTF_8);
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri("/works?filter=title.search:" + encodedTitle + "&sort=cited_by_count:desc");
                
        if (currentKey != null && !currentKey.isBlank()) {
            request.header("Authorization", "Bearer " + currentKey.trim());
        }
        return request.retrieve().body(JsonNode.class);
    }
}