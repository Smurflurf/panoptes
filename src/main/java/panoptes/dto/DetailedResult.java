package panoptes.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Data Transfer Object (DTO) representing a single scientific paper retrieved from the Ideenatlas API.
 * <p>
 * It encapsulates the core metadata of a retrieved document, such as its unique ID, title, 
 * abstract (summary), and its thematic relevance score (cosine similarity distance in the vector space).
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DetailedResult(
        String id, 
        String title, 
        String summary, 
        double score, 
        String contentUrl
) {}