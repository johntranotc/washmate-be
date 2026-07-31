package swp391.carwash.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import swp391.carwash.common.exception.ApiException;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenSeconds;
    private final long refreshTokenSeconds;

    public JwtService(
            @Value("${washmate.security.jwt.secret}") String secret,
            @Value("${washmate.security.jwt.access-token-minutes}") long accessTokenMinutes,
            @Value("${washmate.security.jwt.refresh-token-days}") long refreshTokenDays) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 characters");
        }
        if (accessTokenMinutes <= 0 || refreshTokenDays <= 0) {
            throw new IllegalArgumentException("JWT token lifetime must be positive");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenSeconds = accessTokenMinutes * 60;
        this.refreshTokenSeconds = refreshTokenDays * 24 * 60 * 60;
    }

    public String createAccessToken(AppUserDetails user) {
        return createToken(user, "access", accessTokenSeconds);
    }

    public String createRefreshToken(AppUserDetails user) {
        return createToken(user, "refresh", refreshTokenSeconds);
    }

    public long getAccessTokenSeconds() {
        return accessTokenSeconds;
    }

    public long getRefreshTokenSeconds() {
        return refreshTokenSeconds;
    }

    public Integer extractUserId(String token, String expectedType) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token không hợp lệ hoặc đã hết hạn");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Lỗi phân giải Token");
        }

        String type = claims.get("typ", String.class);
        if (!expectedType.equals(type)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Loại Token không hợp lệ");
        }
        try {
            return Integer.parseInt(claims.getSubject());
        } catch (NumberFormatException e) {
            // Subject bất thường (token hỏng/không đúng định dạng) -> 401 thay vì 500.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token không hợp lệ");
        }
    }

    private String createToken(AppUserDetails user, String type, long ttlSeconds) {
        long now = System.currentTimeMillis();
        long exp = now + (ttlSeconds * 1000);

        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("typ", type)
                .claim("roles", user.getRoleNames())
                .claim("garageIds", user.getGarageIds())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(exp))
                .signWith(key)
                .compact();
    }
}
