package panoptes.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import panoptes.dto.DetailedResult;
import panoptes.dto.ValidatedResult;

/**
 * An epistemic validation service that grounds semantic search results in hard academic metadata.
 * <p>
 * Before a paper from the Ideenatlas can be used by the AI agents, this service queries the
 * <a href="https://openalex.org/">OpenAlex API</a> to retrieve its empirical metadata (citation count,
 * publication year, peer-review status, and retraction status), and, where OpenAlex's own answer is
 * ambiguous or absent, cross-checks against <a href="https://api.crossref.org">Crossref</a>.
 * </p>
 * <p>
 * <b>Why the Crossref cross-check exists:</b> OpenAlex frequently indexes a preprint (arXiv, bioRxiv, ...)
 * as a separate work record from its later peer-reviewed publication, and does not always link the two.
 * A naive single-source lookup will then report a paper as "not peer-reviewed" indefinitely, even years
 * after it was published in a refereed venue. This service treats that outcome as "unresolved, worth a
 * second look" rather than as ground truth, and actively tries to resolve it via (a) Crossref's
 * "is-preprint-of" relation metadata on the preprint's own DOI, and (b) a bibliographic title search
 * restricted to peer-reviewed types.
 * </p>
 * <p>
 * To prevent the LLM from hallucinating connections, this service implements strict string-matching
 * and Jaccard similarity checks to ensure the returned metadata matches the original paper precisely.
 * If a paper is retracted or unverified, it is either flagged or discarded.
 * </p>
 */
@Service
public class PaperValidationService {

    private static final Logger log = LoggerFactory.getLogger(PaperValidationService.class);

    /** OpenAlex + Crossref "type" values that count as genuinely peer-reviewed venues. */
    private static final Set<String> PEER_REVIEWED_TYPES = Set.of(
            "article", "review", "conference-paper", "data-paper", "software-paper", // OpenAlex
            "journal-article", "proceedings-article" // Crossref naming variants
    );

    /** Confirmed peer-reviewed results are trusted longer; unresolved/preprint results are re-checked
     *  much sooner, since publication status is exactly the thing that can change after the fact. */
    private static final Duration TTL_CONFIRMED_PEER_REVIEWED = Duration.ofDays(180);
    private static final Duration TTL_UNCONFIRMED_OR_PREPRINT = Duration.ofDays(14);

    private final RestClient openAlexClient;
    private final RestClient crossrefClient;
    private final ConcurrentHashMap<String, CacheEntry> validationCache = new ConcurrentHashMap<>();
    private final List<String> apiKeys;
    private final AtomicInteger keyCounter = new AtomicInteger(0);

    private record CacheEntry(ValidatedResult result, Instant validUntil) {
        boolean isExpired() {
            return Instant.now().isAfter(validUntil);
        }
    }

    /** Result of a Crossref reconciliation attempt: a peer-reviewed counterpart was found. */
    private record ReconciliationResult(String doi, int citations, boolean isRetracted, Integer year) {
    }

    /** Result of scanning OpenAlex's title-search hits for the best available candidate. */
    private static final class MatchResult {
        JsonNode bestOverall;
        boolean peerReviewedFound;
    }

    public PaperValidationService(
            @Value("${openalex.api-keys:}") List<String> apiKeys,
            @Value("${openalex.email:}") String email) {
        this.apiKeys = apiKeys;

        RestClient.Builder openAlexBuilder = RestClient.builder().baseUrl("https://api.openalex.org");
        if (email != null && !email.isBlank()) {
            openAlexBuilder.defaultHeader("User-Agent", "mailto:" + email);
        }
        this.openAlexClient = openAlexBuilder.build();

        // Crossref's "polite pool" (faster, more reliable responses) is unlocked by a descriptive
        // User-Agent with a contact mailto, same idea as OpenAlex's.
        RestClient.Builder crossrefBuilder = RestClient.builder().baseUrl("https://api.crossref.org");
        if (email != null && !email.isBlank()) {
            crossrefBuilder.defaultHeader("User-Agent", "PanoptesValidator/1.0 (mailto:" + email + ")");
        }
        this.crossrefClient = crossrefBuilder.build();
    }

    public ValidatedResult validatePaper(DetailedResult result) {
        CacheEntry cached = validationCache.get(result.id());
        if (cached != null && !cached.isExpired()) {
            return cached.result();
        }

        ValidatedResult fresh = doValidate(result);
        Duration ttl = (fresh.isPeerReviewed() && fresh.doi() != null)
                ? TTL_CONFIRMED_PEER_REVIEWED
                : TTL_UNCONFIRMED_OR_PREPRINT;
        validationCache.put(result.id(), new CacheEntry(fresh, Instant.now().plus(ttl)));
        return fresh;
    }

    /** Forces re-validation of a specific paper on its next lookup, e.g. after a manual correction. */
    public void invalidate(String resultId) {
        validationCache.remove(resultId);
    }

    /** Periodically drops expired cache entries so the map doesn't grow unbounded over long runs.
     *  Requires {@code @EnableScheduling} on a configuration class in this application. */
    @Scheduled(fixedRate = 6, timeUnit = TimeUnit.HOURS)
    void evictExpiredCacheEntries() {
        validationCache.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private ValidatedResult doValidate(DetailedResult result) {
        String rawTitle = result.title();
        String queryFull = normalizeForQuery(rawTitle);
        String queryShort = shortQuery(rawTitle, queryFull);

        int maxRetries = Math.max(4, apiKeys.isEmpty() ? 4 : apiKeys.size() * 2);

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String currentKey = apiKeys.isEmpty() ? null : apiKeys.get(keyCounter.getAndIncrement() % apiKeys.size());

            try {
                JsonNode response = fetchOpenAlex(queryFull, currentKey);
                if (isEmpty(response)) {
                    response = fetchOpenAlex(queryShort, currentKey);
                }

                if (isEmpty(response)) {
                    // OpenAlex has nothing under either query. Before giving up, try Crossref directly:
                    // OpenAlex's ingestion is not complete for every venue, and this is exactly how a
                    // published PLoS One paper slipped through as "no metadata" in the reference audit.
                    ReconciliationResult r = crossrefBibliographicFallback(rawTitle);
                    sleepQuietly(100);
                    return r != null
                            ? buildResult(result, r.doi(), true, r.citations(), r.isRetracted(), r.year(), "Crossref (Bibliographic Search)")
                            : fallback(result);
                }

                MatchResult match = pickBestMatch(rawTitle, response.path("results"));
                if (match.bestOverall == null) {
                    ReconciliationResult r = crossrefBibliographicFallback(rawTitle);
                    sleepQuietly(100);
                    return r != null
                            ? buildResult(result, r.doi(), true, r.citations(), r.isRetracted(), r.year(), "Crossref (Bibliographic Search)")
                            : fallback(result);
                }

                if (!match.peerReviewedFound) {
                    ReconciliationResult reconciled = reconcileWithPublishedVersion(rawTitle, match.bestOverall, currentKey);
                    if (reconciled != null) {
                        sleepQuietly(100);
                        return buildResult(result, reconciled.doi(), true, reconciled.citations(),
                                reconciled.isRetracted(), reconciled.year(), "Crossref (Reconciled)");
                    }
                }

                sleepQuietly(100);
                return buildResult(
                        result,
                        textOrNull(match.bestOverall, "doi"),
                        match.peerReviewedFound,
                        match.bestOverall.path("cited_by_count").asInt(0),
                        match.bestOverall.path("is_retracted").asBoolean(false),
                        match.bestOverall.hasNonNull("publication_year")
                                ? match.bestOverall.path("publication_year").asInt()
                                : null,
                        "OpenAlex");

            } catch (HttpClientErrorException.TooManyRequests e) {
                long waitMs = retryAfterMillis(e).orElse(1000L * (attempt + 1));
                log.warn("OpenAlex 429 rate limited, backing off {} ms (attempt {}/{})", waitMs, attempt + 1, maxRetries);
                sleepQuietly(waitMs + ThreadLocalRandom.current().nextLong(250));
            } catch (HttpClientErrorException.BadRequest e) {
                log.warn("OpenAlex 400 Bad Request. Unsupported characters in title: '{}'", queryFull);
                break; // special characters, retrying won't help
            } catch (HttpServerErrorException e) {
                log.warn("OpenAlex server error ({}/{}): {} - retrying", attempt + 1, maxRetries, e.getMessage());
                sleepQuietly(500L * (attempt + 1) + ThreadLocalRandom.current().nextLong(250));
            } catch (Exception e) {
                log.warn("OpenAlex API error ({}/{}): '{}' - retrying", attempt + 1, maxRetries, e.toString());
                sleepQuietly(500L * (attempt + 1));
            }
        }

        // BESSER KEINE METADATEN ALS FALSCHE!
        // PREFER NO METADATA OVER WRONG METADATA.
        log.warn("Exhausted retries validating '{}', returning conservative fallback (no metadata).", rawTitle);
        return fallback(result);
    }

    /**
     * Scans OpenAlex's title-search hits and keeps the BEST candidate, not the last one seen.
     * A peer-reviewed match, if any exists among the hits, always wins and short-circuits the scan.
     * Otherwise the first (highest-cited, since results are sorted desc) non-peer match is kept as
     * the fallback candidate — the previous implementation kept overwriting this with every later
     * match, so a run of several non-peer duplicates would silently end up keeping the *worst* of them.
     */
    private MatchResult pickBestMatch(String rawTitle, JsonNode resultsArray) {
        MatchResult mr = new MatchResult();
        JsonNode firstNonPeerMatch = null;

        for (int j = 0; j < Math.min(resultsArray.size(), 50); j++) {
            JsonNode candidate = resultsArray.get(j);
            String candidateTitle = candidate.path("title").asText("");

            if (!isStrictTitleMatch(rawTitle, candidateTitle)) {
                continue;
            }

            if (isPeerReviewedType(candidate)) {
                mr.bestOverall = candidate;
                mr.peerReviewedFound = true;
                break; // best possible outcome for this paper, stop scanning
            }

            if (firstNonPeerMatch == null) {
                firstNonPeerMatch = candidate;
            }
        }

        if (mr.bestOverall == null) {
            mr.bestOverall = firstNonPeerMatch;
        }
        return mr;
    }

    private boolean isPeerReviewedType(JsonNode candidate) {
        String type = candidate.path("type").asText("");
        boolean isPeer = PEER_REVIEWED_TYPES.contains(type);

        // Nature News (and similar news/editorial content masquerading under a DOI) is never a real paper.
        // Extend this list as more false-positive hosts are found.
        String doi = textOrNull(candidate, "doi");
        if (doi != null && doi.toLowerCase().contains("d41586")) {
            isPeer = false;
        }
        return isPeer;
    }

    /**
     * Actively tries to resolve "OpenAlex only knows the preprint" into "here is the peer-reviewed
     * publication", instead of silently accepting the preprint's status as final. Two strategies,
     * tried in order:
     * <ol>
     *   <li>Ask Crossref whether the preprint's own DOI declares an {@code is-preprint-of} relation
     *       to a published DOI (fast and precise; works well for bioRxiv/medRxiv, which routinely
     *       register this relation once a paper is published).</li>
     *   <li>Fall back to a Crossref bibliographic title search restricted to peer-reviewed types
     *       (broader net; needed for preprint servers like arXiv that don't register relation
     *       metadata back to the eventual journal).</li>
     * </ol>
     */
    private ReconciliationResult reconcileWithPublishedVersion(String rawTitle, JsonNode preprintCandidate, String openAlexKey) {
        String preprintDoi = textOrNull(preprintCandidate, "doi");

        if (preprintDoi != null) {
            try {
                JsonNode crossrefWork = crossrefWorkByDoi(preprintDoi);
                JsonNode relation = crossrefWork.path("message").path("relation").path("is-preprint-of");
                if (relation.isArray() && !relation.isEmpty()) {
                    String publishedDoi = relation.get(0).path("id").asText(null);
                    if (publishedDoi != null) {
                        ReconciliationResult r = lookupOpenAlexByDoi(publishedDoi, openAlexKey);
                        if (r != null) {
                            log.info("Reconciled '{}': preprint {} -> published version {} (Crossref relation)",
                                    rawTitle, preprintDoi, publishedDoi);
                            return r;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Crossref relation lookup failed for {}: {}", preprintDoi, e.toString());
            }
        }

        return crossrefBibliographicFallback(rawTitle);
    }

    private ReconciliationResult crossrefBibliographicFallback(String rawTitle) {
        try {
            JsonNode search = crossrefBibliographicSearch(rawTitle);
            JsonNode items = search.path("message").path("items");
            for (JsonNode item : items) {
                String candidateTitle = (item.path("title").isArray() && item.path("title").size() > 0)
                        ? item.path("title").get(0).asText("")
                        : "";
                if (isStrictTitleMatch(rawTitle, candidateTitle)) {
                    String doi = item.path("DOI").asText(null);
                    int citations = item.path("is-referenced-by-count").asInt(0);
                    Integer year = extractCrossrefYear(item);
                    log.info("Reconciled '{}' -> {} via Crossref bibliographic search", rawTitle, doi);
                    return new ReconciliationResult(doi, citations, false, year);
                }
            }
        } catch (Exception e) {
            log.debug("Crossref bibliographic search failed for '{}': {}", rawTitle, e.toString());
        }
        return null;
    }

    private ReconciliationResult lookupOpenAlexByDoi(String doi, String openAlexKey) {
        try {
            String bareDoi = doi.replaceFirst("(?i)^https?://doi\\.org/", "");
            RestClient.RequestHeadersSpec<?> request = openAlexClient.get()
                    .uri("/works/https://doi.org/" + bareDoi);
            if (openAlexKey != null && !openAlexKey.isBlank()) {
                request = request.header("Authorization", "Bearer " + openAlexKey.trim());
            }
            JsonNode work = request.retrieve().body(JsonNode.class);

            String type = work.path("type").asText("");
            if (!PEER_REVIEWED_TYPES.contains(type)) {
                return null;
            }
            return new ReconciliationResult(
                    work.path("doi").asText(doi),
                    work.path("cited_by_count").asInt(0),
                    work.path("is_retracted").asBoolean(false),
                    work.hasNonNull("publication_year") ? work.path("publication_year").asInt() : null);
        } catch (Exception e) {
            log.debug("OpenAlex DOI lookup failed for {}: {}", doi, e.toString());
            return null;
        }
    }

    private JsonNode crossrefWorkByDoi(String doi) {
        String bareDoi = doi.replaceFirst("(?i)^https?://doi\\.org/", "");
        return crossrefClient.get()
                .uri("/works/" + bareDoi)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode crossrefBibliographicSearch(String rawTitle) {
        String encodedTitle = URLEncoder.encode(normalizeForQuery(rawTitle), StandardCharsets.UTF_8);
        return crossrefClient.get()
                .uri("/works?query.bibliographic=" + encodedTitle
                        + "&filter=type:journal-article,type:proceedings-article&rows=5")
                .retrieve()
                .body(JsonNode.class);
    }

    private Integer extractCrossrefYear(JsonNode item) {
        JsonNode parts = item.path("published").path("date-parts");
        if (parts.isArray() && parts.size() > 0 && parts.get(0).isArray() && parts.get(0).size() > 0) {
            return parts.get(0).get(0).asInt();
        }
        return null;
    }

    private JsonNode fetchOpenAlex(String query, String currentKey) {
        String encodedTitle = URLEncoder.encode(query, StandardCharsets.UTF_8);
        RestClient.RequestHeadersSpec<?> request = openAlexClient.get()
                // per-page raised from the implicit default (25) to 50: a companion published
                // record can easily rank outside the top 25 if it has fewer citations than the
                // preprint, which is common shortly after publication.
                .uri("/works?filter=title.search:" + encodedTitle + "&sort=cited_by_count:desc&per-page=50");

        if (currentKey != null && !currentKey.isBlank()) {
            request = request.header("Authorization", "Bearer " + currentKey.trim());
        }
        return request.retrieve().body(JsonNode.class);
    }

    private boolean isEmpty(JsonNode response) {
        return response == null || !response.has("results") || response.path("results").isEmpty();
    }

    private Optional<Long> retryAfterMillis(HttpClientErrorException.TooManyRequests e) {
        List<String> header = e.getResponseHeaders() != null
                ? e.getResponseHeaders().get("Retry-After")
                : null;
        if (header == null || header.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(header.get(0)) * 1000L);
        } catch (NumberFormatException nfe) {
            return Optional.empty();
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalizeForQuery(String rawTitle) {
        return rawTitle.replaceAll("[^\\p{L}\\p{N}\\- ]", "").trim();
    }

    private String shortQuery(String rawTitle, String queryFull) {
        if (rawTitle.contains(":")) {
            return rawTitle.substring(0, rawTitle.indexOf(":")).replaceAll("[^\\p{L}\\p{N}\\- ]", "").trim();
        }
        String[] words = queryFull.split("\\s+");
        return String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 6)));
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private ValidatedResult fallback(DetailedResult result) {
        return new ValidatedResult(result, null, false, 0, false, null, "No Metadata (Fallback)");
    }

	private ValidatedResult buildResult(DetailedResult result, String doi, boolean isPeerReviewed,
	                                     int citations, boolean isRetracted, Integer year, String source) {
	    return new ValidatedResult(result, doi, isPeerReviewed, citations, isRetracted, year, source);
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
}