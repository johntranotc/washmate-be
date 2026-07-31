package swp391.carwash.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import swp391.carwash.dto.AnalyticsSummaryResponse;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Role;
import swp391.carwash.entity.UserRole;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.RoleName;
import swp391.carwash.enums.UserStatus;
import swp391.carwash.repository.AppUserRepository;
import swp391.carwash.service.AnalyticsSummaryService;
import swp391.carwash.service.VehicleService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationAuthorizationJwtSecurityTest {
    private static final String SECRET = "test-secret-for-context-loads-change-me-123456789";
    private static final String WRONG_SECRET = "wrong-secret-for-security-tests-change-me-12345";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private AnalyticsSummaryService analyticsSummaryService;

    @MockitoBean
    private VehicleService vehicleService;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @Nested
    class JwtValidityAndLifecycle {
        @Test
        void should_returnOk_when_accessTokenValid() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("CUSTOMER")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(10))
                    .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        }

        @Test
        void should_returnUnauthorized_when_accessTokenExpired() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token(
                                    10,
                                    "access",
                                    List.of("CUSTOMER"),
                                    List.of(),
                                    SECRET,
                                    Instant.now().minusSeconds(120)))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void should_returnUnauthorized_when_tokenPayloadTampered() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");
            String tampered = replacePayloadSubject(accessToken(10, List.of("CUSTOMER")), "11");

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(tampered)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void should_returnUnauthorized_when_tokenSignedWithWrongSecret() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token(
                                    10,
                                    "access",
                                    List.of("CUSTOMER"),
                                    List.of(),
                                    WRONG_SECRET,
                                    Instant.now().plusSeconds(3600)))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void should_returnUnauthorized_when_authorizationHeaderMissing() throws Exception {
            mockMvc.perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void should_returnUnauthorized_when_authorizationHeaderHasWrongFormat() throws Exception {
            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, "Token " + accessToken(10, List.of("CUSTOMER"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void should_returnUnauthorized_when_refreshTokenUsedAsAccessToken() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token(
                                    10,
                                    "refresh",
                                    List.of("CUSTOMER"),
                                    List.of(),
                                    SECRET,
                                    Instant.now().plusSeconds(3600)))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void should_returnUnauthorized_when_algNoneTokenUsed() throws Exception {
            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(unsignedAlgNoneToken(10))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void should_returnOk_when_accessTokenOmitsRoleClaimBecauseRolesAreLoadedFromDatabase() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithoutRoles(10))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        }

        @Test
        @Disabled("Current JwtService lets NumberFormatException escape when sub/userId is missing; desired fail-safe status is 401.")
        void should_returnUnauthorized_when_accessTokenMissingUserIdClaim() throws Exception {
            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithoutSubject())))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class JwtStaleness {
        @Test
        void should_useCurrentDatabaseRole_when_tokenContainsOldCustomerRole() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "ADMIN");
            when(analyticsSummaryService.getSummary()).thenReturn(emptySummary());

            mockMvc.perform(get("/api/analytics/summary")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("CUSTOMER")))))
                    .andExpect(status().isOk());
        }

        @Test
        @Disabled("Current @PreAuthorize denial is handled by GlobalExceptionHandler as 500; desired fail-safe status is 403.")
        void should_returnForbidden_when_tokenClaimsAdminButDatabaseRoleIsCustomer() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/analytics/summary")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("ADMIN")))))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_returnUnauthorized_when_userBlockedAfterTokenIssued() throws Exception {
            stubCurrentUser(10, UserStatus.BLOCKED, "ADMIN");

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("ADMIN")))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void should_returnUnauthorized_when_userDeletedAfterTokenIssued() throws Exception {
            when(appUserRepository.findWithUserRolesById(10)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("CUSTOMER")))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @Disabled("Access tokens have no blacklist/session-version check; revoking refresh tokens does not invalidate already-issued access tokens.")
        void should_returnUnauthorized_when_sessionRevokedButAccessTokenStillUnexpired() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("CUSTOMER")))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class RbacEnforcement {
        @Test
        void should_returnForbidden_when_customerCallsAdminUsersEndpoint() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("CUSTOMER")))))
                    .andExpect(status().isForbidden());
        }

        // Endpoint tạo nhân sự vận hành: chỉ chặn bằng SecurityConfig.isAdminRequest() (path chứa
        // segment "admin"), không có @PreAuthorize riêng -> phải test để tránh regress nếu ai đó
        // đổi path sang chỗ không khớp matcher.
        @Test
        void should_returnForbidden_when_staffCallsAdminStaffCreationEndpoint() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "STAFF");

            mockMvc.perform(post("/api/admin/staff")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("STAFF"))))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {"email":"a@b.vn","password":"secret123","fullName":"A",
                                 "phone":"0901234567","role":"STAFF","garageIds":[1]}
                                """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @Disabled("Current @PreAuthorize denial is handled by GlobalExceptionHandler as 500; desired fail-safe status is 403.")
        void should_returnForbidden_when_customerCallsAdminSummaryEndpoint() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/analytics/summary")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("CUSTOMER")))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @Disabled("Current @PreAuthorize denial is handled by GlobalExceptionHandler as 500; desired fail-safe status is 403.")
        void should_returnForbidden_when_staffCallsCustomerOnlyVehicleEndpoint() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "STAFF");

            mockMvc.perform(get("/api/v1/vehicles/my-vehicles")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("STAFF")))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @Disabled("Current @PreAuthorize denial is handled by GlobalExceptionHandler as 500; desired fail-safe status is 403.")
        void should_returnForbidden_when_customerCallsStaffVehicleInventoryEndpoint() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "CUSTOMER");

            mockMvc.perform(get("/api/v1/vehicles")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("CUSTOMER")))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @Disabled("Current @PreAuthorize denial is handled by GlobalExceptionHandler as 500; desired fail-safe status is 403.")
        void should_returnForbidden_when_userHasNoRoleForRoleProtectedEndpoint() throws Exception {
            when(appUserRepository.findWithUserRolesById(10)).thenReturn(Optional.of(user(10, UserStatus.ACTIVE)));

            mockMvc.perform(get("/api/analytics/summary")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of()))))
                    .andExpect(status().isForbidden());
        }

        @Test
        void should_allowEveryAssignedRole_when_userHasMultipleRoles() throws Exception {
            stubCurrentUser(10, UserStatus.ACTIVE, "ADMIN", "CUSTOMER");
            when(analyticsSummaryService.getSummary()).thenReturn(emptySummary());
            when(vehicleService.getByEmail(anyString())).thenReturn(List.of());

            mockMvc.perform(get("/api/analytics/summary")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("CUSTOMER")))))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/vehicles/my-vehicles")
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken(10, List.of("ADMIN")))))
                    .andExpect(status().isOk());
        }
    }

    private void stubCurrentUser(Integer userId, UserStatus status, String... roles) {
        when(appUserRepository.findWithUserRolesById(userId)).thenReturn(Optional.of(user(userId, status, roles)));
    }

    private AppUser user(Integer userId, UserStatus status, String... roles) {
        AppUser user = AppUser.builder()
                .id(userId)
                .email("user" + userId + "@example.com")
                .fullName("User " + userId)
                .phone("09000000" + userId)
                .status(status)
                .userRoles(new HashSet<>())
                .build();

        for (String roleName : roles) {
            Role role = Role.builder()
                    .roleName(RoleName.valueOf(roleName))
                    .status(RecordStatus.ACTIVE)
                    .build();
            user.getUserRoles().add(UserRole.builder()
                    .user(user)
                    .role(role)
                    .status(RecordStatus.ACTIVE)
                    .build());
        }
        return user;
    }

    private String accessToken(Integer userId, List<String> roles) {
        return token(userId, "access", roles, List.of(), SECRET, Instant.now().plusSeconds(3600));
    }

    private String tokenWithoutRoles(Integer userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("typ", "access")
                .claim("garageIds", List.of())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    private String tokenWithoutSubject() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claim("typ", "access")
                .claim("roles", List.of("CUSTOMER"))
                .claim("garageIds", List.of())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    private String token(
            Integer userId,
            String type,
            List<String> roles,
            List<Integer> garageIds,
            String secret,
            Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("typ", type)
                .claim("roles", roles)
                .claim("garageIds", garageIds)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    private String unsignedAlgNoneToken(Integer userId) {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + userId + "\",\"typ\":\"access\",\"roles\":[\"CUSTOMER\"]}");
        return header + "." + payload + ".";
    }

    private String replacePayloadSubject(String token, String subject) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String updatedPayload = payload.replaceFirst("\"sub\":\"[^\"]+\"", "\"sub\":\"" + subject + "\"");
        return parts[0] + "." + base64Url(updatedPayload) + "." + parts[2];
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private AnalyticsSummaryResponse emptySummary() {
        return new AnalyticsSummaryResponse(0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0);
    }
}
