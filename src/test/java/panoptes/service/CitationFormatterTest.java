package panoptes.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

public class CitationFormatterTest {

    @Test
    public void testFuzzyMatchDoesNotCrashWithStopwords() throws Exception {
        Method isFuzzyMatch = CitationFormatter.class.getDeclaredMethod("isFuzzyMatch", String.class, String.class);
        isFuzzyMatch.setAccessible(true);

        try {
            // Test case: 100% match after removing stopwords
            boolean isMatch = (boolean) isFuzzyMatch.invoke(null, 
                "The structure of the universe", 
                "Structure of universe"
            );
            
            assertTrue(isMatch, "The method should return true (100% overlap after stopword filtering)");
            System.out.println("✅ SUCCESS: No crashes and matching works correctly!");
            
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException && cause.getMessage().contains("duplicate element")) {
                fail("❌ ERROR: Set.of() is still active in the code!");
            } else {
                throw e; 
            }
        }
    }

    @Test
    public void testFuzzyMatchStrictness() throws Exception {
        Method isFuzzyMatch = CitationFormatter.class.getDeclaredMethod("isFuzzyMatch", String.class, String.class);
        isFuzzyMatch.setAccessible(true);

        // Tests the Math.max() fix. 
        // Short title vs long title should return FALSE!
        boolean isMatch = (boolean) isFuzzyMatch.invoke(null, 
            "Natural Selection", 
            "Conjugate gradient natural selection for machine learning models"
        );
        
        assertFalse(isMatch, "Short titles must NOT blindly match long titles anymore!");
    }
}