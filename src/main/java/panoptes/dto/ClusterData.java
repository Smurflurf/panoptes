package panoptes.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A Data Transfer Object (DTO) representing a thematic cluster of scientific papers.
 * <p>
 * The Ideenatlas groups semantically related documents into hierarchical clusters (using HDBSCAN) 
 * to reveal hidden academic disciplines and thematic neighborhoods. This record holds the aggregated 
 * data for one such cluster, including its AI-generated name, a summary of its conceptual focus, 
 * and a list of specific {@link DetailedResult} documents belonging to it.
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClusterData(
        String clusterId,
        String clusterName,
        double relevanceScore,
        String summary,
        List<DetailedResult> results
) {}