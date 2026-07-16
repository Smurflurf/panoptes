package panoptes;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;

import panoptes.service.CitationFormatter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@SpringBootTest
public class SemanticScholarDebugTest {

    @Test
    @Disabled("Manual debug test - Alternative API evaluation")
    public void debugSemanticScholarPaper() throws Exception {
        // Test Paper 1: Weird results on OpenAlex
        // Test Paper 2: Kosinski's "Theory of Mind" Paper, not on OpenAlex
        String[] testTitles = {
            "Language Models Don’t Learn the Physical Manifestation of Language",
            "Theory of Mind May Have Spontaneously Emerged in Large Language Models"
        };
        
        // Semantic Scholar Base URL
        RestClient restClient = RestClient.builder().baseUrl("https://api.semanticscholar.org").build();

        for (String title : testTitles) {
            System.out.println("\n=========================================");
            System.out.println("🔍 SEMANTIC SCHOLAR DEBUGGER");
            System.out.println("Target: " + title);
            
            // For Semantic Scholar, we can pass the raw title;
            String encodedQuery = URLEncoder.encode(title, StandardCharsets.UTF_8);
            
            // Limit 5 is sufficient, we only need specific fields for the ValidatedResult.
            String url = "/graph/v1/paper/search?query=" + encodedQuery 
                       + "&limit=5&fields=title,url,year,citationCount,publicationTypes,authors";
            
            System.out.println("\n[GET] " + url);
            
            try {
                JsonNode response = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(JsonNode.class);
                        
                if (response == null || !response.has("data") || response.path("data").isEmpty()) {
                    System.out.println("❌ Semantic Scholar found NO results.");
                    continue;
                }
                
                JsonNode results = response.path("data");
                System.out.println("✅ Found top results in Semantic Scholar.");
                System.out.println("Showing the Top candidates:\n");
                
                int i = 1;
                for (JsonNode paper : results) {
                    String candidateTitle = paper.path("title").asText("");
                    
                    System.out.println("--- Result [" + i++ + "] ---");
                    System.out.println("ID:        " + paper.path("paperId").asText(""));
                    System.out.println("Title:     " + candidateTitle);
                    System.out.println("Year:      " + (paper.hasNonNull("year") ? paper.path("year").asInt() : "N/A"));
                    System.out.println("Citations: " + paper.path("citationCount").asInt(0));
                    System.out.println("URL:       " + paper.path("url").asText(""));
                    
                    // Show authors briefly to verify correctness (e.g., Kosinski)
                    if (paper.has("authors") && paper.get("authors").isArray()) {
                        StringBuilder authors = new StringBuilder();
                        for (JsonNode author : paper.get("authors")) {
                            authors.append(author.path("name").asText("")).append(", ");
                        }
                        System.out.println("Authors:   " + authors.toString());
                    }
                    
                    // Check publication type 
                    boolean isPeerReviewed = false;
                    if (paper.has("publicationTypes") && paper.get("publicationTypes").isArray() && paper.get("publicationTypes").size() > 0) {
                        System.out.print("PubTypes:  ");
                        for (JsonNode pubType : paper.get("publicationTypes")) {
                            String pt = pubType.asText("");
                            System.out.print(pt + " ");
                            if (pt.equals("JournalArticle") || pt.equals("Conference") || pt.equals("Review")) {
                                isPeerReviewed = true;
                            }
                        }
                        System.out.println();
                    } else {
                        System.out.println("PubTypes:  None (Likely Preprint)");
                    }
                    
                    System.out.println("Peer-Rev.: " + (isPeerReviewed ? "🟢 YES" : "🔴 NO"));
                    
                    // Internal pipeline matcher check
                    boolean isMatch = CitationFormatter.isFuzzyMatch(title, candidateTitle);
                    System.out.println(">> Pipeline Match? " + (isMatch ? "🟢 YES" : "🔴 NO"));
                    System.out.println();
                }
            } catch (Exception e) {
                System.out.println("❌ API Error: " + e.getMessage());
            }
            System.out.println("=========================================\n");
            
            // Small pause to prevent rate limits at S2 without an API key
            Thread.sleep(1500); 
        }
    }
}