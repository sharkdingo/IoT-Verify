package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.configure.JwtConfig;
import cn.edu.nju.Iot_Verify.configure.ProductionSafetyCheck;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtUtil {

    private static final String INSECURE_DEFAULT_PREFIX = "iot-verify-secret-key";

    private final JwtConfig config;
    private final Environment environment;

    private SecretKey signingKey;
    private JwtParser parser;

    public JwtUtil(JwtConfig config, Environment environment) {
        this.config = config;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        byte[] keyBytes = config.getSecret().getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        // The built parser is immutable and thread-safe; provider discovery belongs to initialization.
        this.parser = Jwts.parserBuilder().setSigningKey(signingKey).build();

        if (config.getSecret().startsWith(INSECURE_DEFAULT_PREFIX) && ProductionSafetyCheck.isProductionProfile(environment)) {
            log.warn("JWT secret is still using the insecure default value — "
                    + "configure jwt.secret (or JWT_SECRET env) for production!");
        }
    }

    /* The production-profile decision has one owner; see ProductionSafetyCheck.isProductionProfile. This class
       duplicated the profile set, the case fold and the loop, and the fold was the part that had drifted. */

    private SecretKey getSigningKey() {
        return signingKey;
    }

    public String generateToken(Long userId, String phone, long authVersion) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + config.getExpiration());

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("phone", phone)
                .claim("authVersion", authVersion)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public TokenIdentity parseIdentity(String token) {
        Claims claims = parseClaims(token);
        try {
            long userId = Long.parseLong(claims.getSubject());
            Object version = claims.get("authVersion");
            if (claims.getExpiration() == null || userId <= 0
                    || !(version instanceof Integer || version instanceof Long)
                    || ((Number) version).longValue() < 0) {
                throw UnauthorizedException.invalidToken();
            }
            return new TokenIdentity(userId, ((Number) version).longValue());
        } catch (NumberFormatException e) {
            throw UnauthorizedException.invalidToken();
        }
    }

    public record TokenIdentity(Long userId, long authVersion) { }
    /**
     * 获取Token剩余过期时间（秒）
     * 用于黑名单设置合理的TTL
     */
    public long getExpirationSeconds(String token) {
        Claims claims = parseClaims(token);
        long expMillis = claims.getExpiration().getTime();
        long nowMillis = System.currentTimeMillis();
        return Math.max(60, (expMillis - nowMillis) / 1000);
    }

    private Claims parseClaims(String token) {
        return parser.parseClaimsJws(token)
                .getBody();
    }
}
