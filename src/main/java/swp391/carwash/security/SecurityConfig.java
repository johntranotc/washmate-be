package swp391.carwash.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorResponseWriter securityErrorResponseWriter;

    @Value("${washmate.security.docs-enabled:false}")
    private boolean docsEnabled;

    @Value("${washmate.security.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOriginPatterns;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(this::isPublicRequest).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/payments/vnpay/ipn",
                                "/api/payments/vnpay/return",
                                "/api/v1/garages",
                                "/api/v1/garages/**").permitAll()
                        // Chặn MỌI path có đoạn "admin" nằm ở bất kỳ tầng nào sau /api/
                        // (vd /api/admin/**, /api/v1/admin/**, /api/v1/analytics/admin/**) — dùng ** thay vì *
                        // để controller mới dù đặt path admin lồng sâu cũng không lọt xuống authenticated().
                        // Segment-aware matcher also supports nested /admin/ paths without an invalid mid-pattern **.
                        .requestMatchers(this::isAdminRequest).hasAnyRole("ADMIN", "OWNER")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                securityErrorResponseWriter.write(response, org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                securityErrorResponseWriter.write(response, org.springframework.http.HttpStatus.FORBIDDEN, "Access denied"))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private boolean isPublicRequest(HttpServletRequest request) {
        String path = PublicPaths.stripContextPath(request.getRequestURI(), request.getContextPath());
        return PublicPaths.isPublicPath(path)
                || (docsEnabled && PublicPaths.isDocsPath(path));
    }

    private boolean isAdminRequest(HttpServletRequest request) {
        String path = PublicPaths.stripContextPath(request.getRequestURI(), request.getContextPath());
        if (!path.startsWith("/api/")) {
            return false;
        }
        return Arrays.stream(path.substring("/api/".length()).split("/"))
                .anyMatch("admin"::equals);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
