package panoptes.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The primary response wrapper for results returned by the Ideenatlas vector search API.
 * <p>
 * Instead of providing a single linear list of keyword matches, the Ideenatlas returns results 
 * segmented by conceptual proximity to foster cross-disciplinary serendipity. This DTO encapsulates:
 * <ul>
 *   <li>{@code detailedSimilarResults}: Direct, highly specific semantic matches to the search query (classic RAG).</li>
 *   <li>{@code similarTopicFields}: Papers from conceptually neighboring thematic clusters (cluster RAG).</li>
 *   <li>{@code serendipitousConnections}: Papers from distant but structurally analogous disciplines, 
 *       designed to break down academic silos and reveal unexpected solutions (cluster RAG + inhouse algorithm).</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonApiResponseDto(
        List<ClusterData> similarTopicFields,
        List<ClusterData> serendipitousConnections,
        List<DetailedResult> detailedSimilarResults
) {}