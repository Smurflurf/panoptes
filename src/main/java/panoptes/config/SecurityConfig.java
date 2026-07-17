package panoptes.config;

import java.io.IOException;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Global Security Configuration for the Panoptes application.
 * <p>
 * This configuration enforces strict modern web security standards, including:
 * <ul>
 *   <li><b>CSRF Protection:</b> Mitigated via Double-Submit Cookie pattern.</li>
 *   <li><b>Content Security Policy (CSP):</b> Strictly locked down to 'self' and specific CDNs (KaTeX, Marked, FontAwesome, Tippy).</li>
 *   <li><b>Permissions Policy:</b> Disables camera, geolocation, etc., but explicitly allows the microphone for audio recording.</li>
 *   <li><b>Security Headers:</b> Enforces HSTS, prevents Clickjacking, and sets strict Referrer policies.</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Setup CSRF to use cookies so the frontend JavaScript can read the token
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict"));
        
        http
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        
        // 1. CSRF Protection
        .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
        )
        // Force the CSRF token to be generated and sent to the client
        .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
        
        // 2. Security Headers
        .headers(headers -> headers
                // A. HSTS (Strict Transport Security)
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .preload(true)
                        .maxAgeInSeconds(31536000)
                )
                // B. Clickjacking Protection
                .frameOptions(frame -> frame.deny())

                // C. Content Security Policy (CSP) - Adapted for Panoptes CDNs
                .contentSecurityPolicy(csp -> csp
                        .policyDirectives(
                                "default-src 'self'; " +
                                "base-uri 'self'; " + 
                                "form-action 'self'; " + 
                                // Allow scripts from local and our CDNs
                                "script-src 'self' https://cdn.jsdelivr.net https://unpkg.com; " +
                                // Allow styles from local, CDNs, and inline (required by some libraries)
                                "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://unpkg.com https://cdn.jsdelivr.net; " +
                                // Allow fonts from local, data-URIs, and FontAwesome CDN
                                "font-src 'self' data: https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
                                "img-src 'self' data: blob:; " +
                                // Allow SSE streams and API calls to self
                                "connect-src 'self'; " +
                                "media-src 'self' blob:; " +
                                "worker-src 'self' blob:; " +
                                "object-src 'none'; " +
                                "frame-ancestors 'none';"
                        )
                )

                // D. Permissions Policy (Crucial: Microfon is allowed, everything else blocked)
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", 
                        "microphone=(self), " +
                        "camera=(), " +
                        "geolocation=(), " +
                        "payment=(), " +
                        "usb=()"
                ))

                // E. Referrer Policy 
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
        )

        // 3. Authorization (Strictly adapted for Panoptes routes)
        .authorizeHttpRequests(authz -> authz
                // Allow CORS preflight requests
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                
                // Allow static resources
                .requestMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                
                // Allow UI Pages
                .requestMatchers("/", "/status/**", "/results/**").permitAll()
                
                // Allow API Endpoints
                .requestMatchers("/api/research/**").permitAll()
                
                // Block everything else
                .anyRequest().denyAll() 
        );

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Adjust these to your actual production domains later!
        config.setAllowedOrigins(List.of("http://localhost:8080", "http://localhost:9090")); 
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowCredentials(true); 
        config.setAllowedHeaders(List.of("Authorization", "Cache-Control", "Content-Type", "X-XSRF-TOKEN"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
    
    /**
     * A filter that forces the CSRF token to be generated and added as a cookie.
     * Required for Single Page Applications (SPAs) making AJAX/Fetch requests.
     */
    private static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
            if (csrfToken != null) {
                // This triggers the token generation and adds the Set-Cookie header
                csrfToken.getToken(); 
            }
            filterChain.doFilter(request, response);
        }
    }
}