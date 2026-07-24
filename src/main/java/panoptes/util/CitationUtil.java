package panoptes.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import panoptes.dto.ValidatedResult;

/**
 * A stateless utility class for manipulating and enriching inline XML citations.
 * <p>
 * Throughout the drafting process, the LLM generates simplified citation tags 
 * (e.g., {@code <cite id="123" quote="..."></cite>}). This utility parses the draft, 
 * cross-references the IDs with the validated paper database, and dynamically injects 
 * hard, unhallucinated empirical metadata (like publication year, peer-review status, 
 * and citation count) directly into the XML attributes. This hidden metadata is later 
 * used by subsequent agents to weigh the epistemic strength of the claims.
 * </p>
 */
public class CitationUtil {
    
    /**
     * Injects hard, unhallucinated metadata into the XML tags for subsequent agents.
     */
    public static String enrichCiteTags(String text, Map<String, ValidatedResult> paperDatabase) {
        if (text == null || text.isBlank()) return text;
        
        Pattern citePattern = Pattern.compile("`?\\\\?<cite\\b[^>]*id=[\"']([^\"']+)[\"'][^>]*>.*?</cite>`?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = citePattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (m.find()) {
            String fullTag = m.group();
            String id = m.group(1);
            ValidatedResult vr = paperDatabase.get(id);
            
            if (vr != null) {
                String quote = "";
                Matcher qm = Pattern.compile("quote\\s*=\\s*[\"'](.*?)[\"']", Pattern.DOTALL).matcher(fullTag);
                if (qm.find()) {
                    quote = qm.group(1).replace("\"", "&quot;")
                                       .replace("'", "&#39;")
                                       .replace("<", "&lt;")
                                       .replace(">", "&gt;")
                                       .replaceAll("[\\r\\n]+", " ");
                }
                
                String year = vr.publicationYear() != null ? String.valueOf(vr.publicationYear()) : "Unknown";
                String pr = vr.isPeerReviewed() ? "YES" : "NO";
                String cit = String.valueOf(vr.citationCount());
                String source = vr.verificationSource() != null ? vr.verificationSource() : "Unknown"; 

                String newTag = "<cite id=\"" + id + "\" year=\"" + year + "\" peer_reviewed=\"" + pr + 
                                "\" citations=\"" + cit + "\" source=\"" + source + "\" quote=\"" + quote + "\"></cite>";
                m.appendReplacement(sb, Matcher.quoteReplacement(newTag));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(fullTag));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}