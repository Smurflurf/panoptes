package panoptes.dto;

/**
 * A comprehensive wrapper linking a raw Ideenatlas search result with robust academic metadata.
 * <p>
 * While the Ideenatlas excels at semantic discovery and cross-disciplinary serendipity, 
 * the Panoptes pipeline requires strict epistemic validation before using a source in a final report. 
 * This record binds the original {@link DetailedResult} to hard empirical metadata (such as citation 
 * count, peer-review status, publication year, and retraction status) typically retrieved from external 
 * validation services like OpenAlex.
 * </p>
 */
public record ValidatedResult(
        DetailedResult originalResult,
        String doi,
        boolean isPeerReviewed,
        int citationCount,
        boolean isRetracted,
        Integer publicationYear 
) {}