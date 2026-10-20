package cn.edu.nju.Iot_Verify.security;

import cn.edu.nju.Iot_Verify.configure.JwtConfig;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.service.impl.RedisTokenBlacklistService;
import cn.edu.nju.Iot_Verify.service.TokenBlacklistService;
import cn.edu.nju.Iot_Verify.util.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterCredentialVersionTest {
    private static final String TEST_SECRET = "account-test-signing-key-".repeat(3);
    private final UserRepository users = mock(UserRepository.class);
    private final TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
    private JwtUtil jwt;
    private UserPo user;

    @BeforeEach
    void setUp() {
        var config = new JwtConfig();
        config.setSecret(TEST_SECRET);
        jwt = new JwtUtil(config, new MockEnvironment());
        jwt.init();
        user = UserPo.builder().id(7L).phone("13800138000").username("Alice").build();
        when(users.findById(7L)).thenAnswer(ignored -> Optional.of(user));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        UserContextHolder.clear();
    }

    @Test
    void everyLoginHasAnIndependentTokenEvenWithinOneSecond() {
        String first = jwt.generateToken(7L, user.getPhone(), 0L);
        String second = jwt.generateToken(7L, user.getPhone(), 0L);

        assertNotEquals(first, second);
        assertEquals(new JwtUtil.TokenIdentity(7L, 0L), jwt.parseIdentity(first));
        assertEquals(new JwtUtil.TokenIdentity(7L, 0L), jwt.parseIdentity(second));
    }

    @Test
    void renameKeepsAllExistingTokensButPasswordVersionRevokesAllOfThem() throws Exception {
        String first = jwt.generateToken(7L, user.getPhone(), 0L);
        String second = jwt.generateToken(7L, user.getPhone(), 0L);
        user.setUsername("Carol");
        assertEquals(7L, authenticate(first, blacklist));
        assertEquals(7L, authenticate(second, blacklist));

        user.setAuthVersion(1L);

        assertNull(authenticate(first, blacklist));
        assertNull(authenticate(second, blacklist));
        assertEquals(7L, authenticate(jwt.generateToken(7L, user.getPhone(), 1L), blacklist));
        verify(users, times(5)).findById(7L);
    }

    @Test
    void passwordRevocationStillWorksWhenRedisBlacklistFailsOpen() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        var failOpenBlacklist = new RedisTokenBlacklistService(redis);
        String oldToken = jwt.generateToken(7L, user.getPhone(), 0L);
        user.setAuthVersion(1L);

        assertNull(authenticate(oldToken, failOpenBlacklist));
        assertEquals(7L, authenticate(jwt.generateToken(7L, user.getPhone(), 1L), failOpenBlacklist));
    }

    @Test
    void missingFractionalNegativeAndStringVersionsAreRejectedWithoutAUserQuery() throws Exception {
        for (Object version : new Object[] {null, 0.5, -1L, "0"}) {
            String token = signedToken("7", version, true);
            assertThrows(UnauthorizedException.class, () -> jwt.parseIdentity(token));
            assertNull(authenticate(token, blacklist));
        }
        verify(users, never()).findById(anyLong());
    }

    @Test
    void signedTokensWithoutExpiryOrWithInvalidUserIdentityAreRejected() {
        assertThrows(UnauthorizedException.class, () -> jwt.parseIdentity(signedToken("7", 0L, false)));
        assertThrows(UnauthorizedException.class, () -> jwt.parseIdentity(signedToken("invalid", 0L, true)));
        assertThrows(UnauthorizedException.class, () -> jwt.parseIdentity(signedToken("-1", 0L, true)));
    }

    @Test
    void parsingDoesNotRepeatProviderDiscoveryAfterInitialization() {
        String token = jwt.generateToken(7L, user.getPhone(), 0L);
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        AtomicInteger providerLookups = new AtomicInteger();
        ClassLoader trackingLoader = new ClassLoader(originalLoader) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.startsWith("META-INF/services/io.jsonwebtoken.")) providerLookups.incrementAndGet();
                return super.getResources(name);
            }
        };
        try {
            Thread.currentThread().setContextClassLoader(trackingLoader);
            assertEquals(new JwtUtil.TokenIdentity(7L, 0L), jwt.parseIdentity(token));
            assertTrue(jwt.getExpirationSeconds(token) >= 60);
            assertEquals(0, providerLookups.get());
        } finally {
            Thread.currentThread().setContextClassLoader(originalLoader);
        }
    }

    @Test
    void concurrentParsingKeepsIdentitiesIsolatedAndValidatesEveryToken() throws Exception {
        String expired = Jwts.builder().setSubject("7").claim("authVersion", 0L)
                .setExpiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256).compact();
        String wrongSignature = Jwts.builder().setSubject("7").claim("authVersion", 0L)
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("another-account-signing-key-".repeat(3).getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256).compact();
        var tasks = new ArrayList<Callable<Void>>();
        for (long id = 1; id <= 64; id++) {
            var identity = new JwtUtil.TokenIdentity(id, id);
            String token = jwt.generateToken(id, user.getPhone(), id);
            tasks.add(() -> {
                for (int round = 0; round < 8; round++) {
                    assertEquals(identity, jwt.parseIdentity(token));
                    assertThrows(ExpiredJwtException.class, () -> jwt.parseIdentity(expired));
                    assertThrows(SignatureException.class, () -> jwt.parseIdentity(wrongSignature));
                }
                return null;
            });
        }
        var executor = Executors.newFixedThreadPool(64);
        try {
            for (var result : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) result.get();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Long authenticate(String token, TokenBlacklistService blacklistService) throws Exception {
        SecurityContextHolder.clearContext();
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        AtomicReference<Long> principal = new AtomicReference<>();
        new JwtAuthenticationFilter(jwt, blacklistService, users, mock(HandlerExceptionResolver.class)).doFilter(
                request, new MockHttpServletResponse(), (req, res) -> {
                    principal.set(UserContextHolder.getUserId());
                });
        assertNull(UserContextHolder.getUserId());
        return principal.get();
    }

    private String signedToken(String subject, Object version, boolean includeExpiry) {
        var builder = Jwts.builder().setSubject(subject);
        if (version != null) builder.claim("authVersion", version);
        if (includeExpiry) builder.setExpiration(new Date(System.currentTimeMillis() + 60_000));
        return builder.signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)),
                SignatureAlgorithm.HS256).compact();
    }
}
