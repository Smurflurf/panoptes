package panoptes.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import panoptes.dto.ValidatedResult;

/**
 * A post-processing engine that transforms raw LLM drafts into a beautifully formatted academic report.
 * <p>
 * Throughout the pipeline, agents use strict XML tags (e.g., {@code <cite id="123" quote="...">}) 
 * to reference sources. This formatter parses the final assembled draft, cross-references every XML 
 * tag with the validated paper database, and generates:
 * <ul>
 *   <li>A dynamically numbered, sequentially ordered bibliography.</li>
 *   <li>Inline HTML citation links with hover tooltips containing exact quotes and metadata.</li>
 *   <li>Visual indicators for peer-review status and citation impact.</li>
 * </ul>
 * </p>
 */
public class CitationFormatter {

    static class Reference {
        int index;
        String title;
        String validUrl;
        ValidatedResult vr; 
        List<String> quotes = new ArrayList<>();

        public Reference(int index, String title, String validUrl, ValidatedResult vr) {
            this.index = index;
            this.title = title;
            this.validUrl = validUrl;
            this.vr = vr;
        }
    }

    static class PendingReplacement {
        String key;
        int quoteIndex; 
        String finalUrl;
        String tooltip;

        public PendingReplacement(String key, int quoteIndex, String finalUrl, String tooltip) {
            this.key = key;
            this.quoteIndex = quoteIndex;
            this.finalUrl = finalUrl;
            this.tooltip = tooltip;
        }
    }

    public static String formatReport(String rawReport, Map<String, ValidatedResult> paperDatabase) {
        if (rawReport == null || rawReport.isBlank()) return rawReport;

        Map<String, Reference> bibliography = new LinkedHashMap<>();
        Map<String, PendingReplacement> placeholders = new HashMap<>();
        String processedText = rawReport;

        // Allows <cite id="123"></cite>
        Pattern citePattern = Pattern.compile("`?\\\\?<cite\\b[^>]*id=[\"']([^\"']+)[\"'][^>]*>.*?</cite>`?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher citeMatcher = citePattern.matcher(processedText);
        StringBuffer sb1 = new StringBuffer();

        // After the TL;DR and SilverPlate response, the Paper IDs should begin from 1 and count up.
        // So here we search the start of the real text.
        // The SilverPlate Agent has a hard coded "---" after his response, that is our anchor.
        int mainTextStart = processedText.indexOf("\n\n---\n\n");
        
        if (mainTextStart != -1) {
            mainTextStart += 7; 
        } else {
            mainTextStart = 0; 
        }
        
        Matcher preMatcher = citePattern.matcher(processedText.substring(mainTextStart));
        while (preMatcher.find()) {
            String id = preMatcher.group(1);
            ValidatedResult vr = findInDatabase(paperDatabase, id, null);
            String key = (vr != null) ? vr.originalResult().id() : id;
            String title = (vr != null) ? vr.originalResult().title() : "Unknown Source";
            String finalUrl = (vr != null && vr.originalResult().contentUrl() != null) ? vr.originalResult().contentUrl() : "#"; 
            
            // Counts the Citations as they appear in the main text.
            bibliography.computeIfAbsent(key, k -> new Reference(bibliography.size() + 1, title, finalUrl, vr));
        }
        
        while (citeMatcher.find()) {
            String id = citeMatcher.group(1);
            
            // 1. Get real data from the DB
            ValidatedResult vr = findInDatabase(paperDatabase, id, null);
            
            String title = (vr != null) ? vr.originalResult().title() : "Unknown Source";
            String key = (vr != null) ? vr.originalResult().id() : id;
            String finalUrl = (vr != null && vr.originalResult().contentUrl() != null) 
                                ? vr.originalResult().contentUrl() : "#"; 

            Reference ref = bibliography.computeIfAbsent(key, k -> new Reference(bibliography.size() + 1, title, finalUrl, vr));
            
            // 2. Get quote directly from the LLM-Tag
            String fullTag = citeMatcher.group();
            String quote = extractAttr(fullTag, "quote");
            
            // Fallback if LLM-Tag is missing.
            if (quote == null || quote.isBlank()) {
                if (vr != null && vr.originalResult().summary() != null) {
                    quote = vr.originalResult().summary().split("(?<=[.!?])\\s+")[0].trim();
                } else {
                    quote = title;
                }
            }
            
            int quoteIndex = -1;
            if (quote != null && !quote.isBlank()) {
                if (!ref.quotes.contains(quote)) {
                    ref.quotes.add(quote);
                }
                quoteIndex = ref.quotes.indexOf(quote);
            }
            
            String safeTitle = title.replace("\"", "&quot;").replace("'", "&#39;");
            // Clean up
            String escapedQuote = quote != null ? quote.replace("\"", "&quot;")
                                                       .replace("'", "&#39;")
                                                       .replace("<", "&lt;")
                                                       .replace(">", "&gt;")
                                                       .replaceAll("[\\r\\n]+", " ") : safeTitle;
            
            String uuid = UUID.randomUUID().toString();
            placeholders.put(uuid, new PendingReplacement(key, quoteIndex, finalUrl, escapedQuote));
            
            citeMatcher.appendReplacement(sb1, uuid);
        }
        citeMatcher.appendTail(sb1);
        processedText = sb1.toString();

        for (Map.Entry<String, PendingReplacement> entry : placeholders.entrySet()) {
            String uuid = entry.getKey();
            PendingReplacement p = entry.getValue();
            Reference ref = bibliography.get(p.key);
            
            String displayIndex = (p.quoteIndex >= 0 && ref.quotes.size() > 1) 
                                    ? ref.index + "." + (p.quoteIndex + 1) 
                                    : String.valueOf(ref.index);
            
            String replacement = "<a href=\"" + p.finalUrl + "\" target=\"_blank\" class=\"citation-link\" title=\"" + p.tooltip + "\" data-tippy-content=\"" + p.tooltip + "\">[" + displayIndex + "]</a>";
            processedText = processedText.replace(uuid, replacement);
        }

        if (bibliography.isEmpty()) return processedText;

        StringBuilder finalOutput = new StringBuilder(processedText);
        finalOutput.append("\n\n---\n\n## References\n");

		for (Reference ref : bibliography.values()) {
			String marker = ref.index + ". ";
			String indent = " ".repeat(marker.length()); 

			finalOutput.append(marker).append("**[").append(ref.title).append("](")
					.append(ref.validUrl != null ? ref.validUrl : "").append(")**\n");

			if (ref.vr != null) {
				List<String> metaParts = new ArrayList<>();
				if (ref.vr.publicationYear() != null && ref.vr.publicationYear() > 0) {
					metaParts.add("Published: " + ref.vr.publicationYear());
				}

				if (ref.vr.isPeerReviewed()) {
				    String doiUrl = (ref.vr.doi() != null && !ref.vr.doi().isBlank()) ? ref.vr.doi() : "";
				    String sourceTag = ref.vr.verificationSource() != null ? " <i>(" + ref.vr.verificationSource() + ")</i>" : "";
				    
				    if (!doiUrl.isBlank()) {
				        metaParts.add("Peer-Reviewed: <a href=\"" + doiUrl + "\" target=\"_blank\" class=\"meta-yes\">YES</a>" + sourceTag);
				    } else {
				        metaParts.add("Peer-Reviewed: <span class=\"meta-yes\">YES</span>" + sourceTag);
				    }
				} else {
				    String sourceTag = ref.vr.verificationSource() != null ? " <i>(" + ref.vr.verificationSource() + ")</i>" : "";
				    metaParts.add("Peer-Reviewed: <span class=\"meta-no\">NO</span>" + sourceTag);
				}

				int cit = ref.vr.citationCount();
				String citClass = cit == 0 ? "meta-cit-bad" : (cit < 10 ? "meta-cit-ok" : "meta-cit-good");
				metaParts.add("Citations: <span class=\"" + citClass + "\">" + cit + "</span>");

				finalOutput.append(indent).append("*").append(String.join(" | ", metaParts)).append("*\n");

				if (ref.vr.doi() != null && !ref.vr.doi().isBlank()) {
					String cleanDoi = ref.vr.doi().replace("https://doi.org/", "");
					finalOutput.append(indent).append("*DOI: [").append(cleanDoi).append("](").append(ref.vr.doi())
							.append(")*\n");
				}
			}

			if (!ref.quotes.isEmpty()) {
				finalOutput.append("\n");
				for (int i = 0; i < ref.quotes.size(); i++) {
					String label = (ref.quotes.size() > 1) ? (ref.index + "." + (i + 1)) : String.valueOf(ref.index);

					finalOutput.append(indent).append("> **[").append(label).append("]** *\"").append(ref.quotes.get(i))
							.append("\"*\n");

					if (i < ref.quotes.size() - 1) {
						finalOutput.append(indent).append(">\n").append(indent).append("> ---\n").append(indent)
								.append(">\n");
					}
				}
				finalOutput.append("\n");
			} else {
				finalOutput.append("\n");
			}
		}
		
		String cleanedOutput = finalOutput.toString()
		    .replaceAll("\\s+\\[([0-9.]+)\\]\\s+\\.", " [$1].")
		    .replaceAll("\\s+\\[([0-9.]+)\\]\\s+,", " [$1],");
		return cleanedOutput;    }

    private static ValidatedResult findInDatabase(Map<String, ValidatedResult> db, String id, String title) {
        if (id != null && db.containsKey(id)) {
            return db.get(id);
        }
        if (title != null && !title.isBlank()) {
            String cleanTarget = title.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            for (ValidatedResult v : db.values()) {
                String cleanDbTitle = v.originalResult().title().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
                if (cleanDbTitle.contains(cleanTarget) || cleanTarget.contains(cleanDbTitle)) {
                    return v;
                }
                if (isFuzzyMatch(title, v.originalResult().title())) {
                    return v;
                }
            }
        }
        return null;
    }

    public static boolean isFuzzyMatch(String t1, String t2) {
        if (t1 == null || t2 == null) return false;

        String norm1 = t1.toLowerCase().replaceAll("[^a-z0-9]", "");
        String norm2 = t2.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (norm1.equals(norm2)) return true;

        Set<String> set1 = new HashSet<>(Arrays.asList(t1.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", "").split("\\s+")));
        Set<String> set2 = new HashSet<>(Arrays.asList(t2.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", "").split("\\s+")));
        
        Set<String> stopWords = new HashSet<>(Arrays.asList(
            "a", "an", "the", "in", "on", "of", "and", "or", "to", "for", "with", "by", "as", "from",
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "eines", "im", "am", "um", "und", "oder", "für", "mit", "von", "zu", "auf",
            "el", "la", "los", "las", "un", "una", "unos", "unas", "en", "de", "y", "o", "para", "con", "por",
            "le", "les", "une", "des", "et", "ou", "à", "pour", "avec", "par"
        ));
        
        set1.removeAll(stopWords);
        set2.removeAll(stopWords);
        
        if (set1.isEmpty() || set2.isEmpty()) return false;
        
        int overlap = 0;
        for (String w : set1) {
            if (set2.contains(w)) overlap++;
        }
        
        double matchScore = (double) overlap / Math.max(set1.size(), set2.size());
        return matchScore >= 0.85; 
    }

    private static String extractAttr(String attrs, String attrName) {
        Pattern p = Pattern.compile(attrName + "\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(attrs);
        if (m.find()) return m.group(2);
        return null;
    }
}