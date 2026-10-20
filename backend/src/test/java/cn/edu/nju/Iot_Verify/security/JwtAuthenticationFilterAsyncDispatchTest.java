package cn.edu.nju.Iot_Verify.security;

import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.service.TokenBlacklistService;
import cn.edu.nju.Iot_Verify.util.JwtUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterAsyncDispatchTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HandlerExceptionResolver exceptionResolver;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtUtil, tokenBlacklistService, userRepository, exceptionResolver);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        UserContextHolder.clear();
    }

    @Test
    void shouldNotFilterAsyncDispatch_returnsFalse() {
        assertFalse(filter.shouldNotFilterAsyncDispatch());
    }

    @Test
    void doFilter_asyncDispatch_appliesJwtAuthentication() throws Exception {
        String token = "valid-token";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.ASYNC);
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);

        when(jwtUtil.parseIdentity(token)).thenReturn(new JwtUtil.TokenIdentity(42L, 0L));
        when(tokenBlacklistService.isBlacklisted(token)).thenReturn(false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(UserPo.builder().id(42L).build()));

        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(chainCalled.get());
        assertNotNull(authentication);
        assertEquals(42L, authentication.getPrincipal());
        verify(jwtUtil).parseIdentity(token);
        verify(tokenBlacklistService).isBlacklisted(token);
        verify(userRepository).findById(42L);
    }

    @Test
    void threadLocalClearedAfterFilterReturns_preventsLeakToNextRequest() throws Exception {
        String token = "valid-token";

        // Request 1: authenticated request that sets UserContextHolder
        MockHttpServletRequest request1 = new MockHttpServletRequest();
        request1.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        AtomicReference<Long> userIdDuringChain1 = new AtomicReference<>();
        FilterChain chain1 = (req, res) -> {
            userIdDuringChain1.set(UserContextHolder.getUserId());
        };

        when(jwtUtil.parseIdentity(token)).thenReturn(new JwtUtil.TokenIdentity(42L, 0L));
        when(tokenBlacklistService.isBlacklisted(token)).thenReturn(false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(UserPo.builder().id(42L).build()));

        filter.doFilter(request1, response1, chain1);

        // Assert: during chain, userId was 42
        assertEquals(42L, userIdDuringChain1.get());
        // Assert: after filter returns, UserContextHolder was cleared
        assertNull(UserContextHolder.getUserId());

        // Request 2: no Authorization header, simulating reuse of the same thread
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        AtomicReference<Long> userIdDuringChain2 = new AtomicReference<>();
        FilterChain chain2 = (req, res) -> {
            userIdDuringChain2.set(UserContextHolder.getUserId());
        };

        filter.doFilter(request2, response2, chain2);

        // Assert: during chain, userId was null (not leaked from request1)
        assertNull(userIdDuringChain2.get());
        // Assert: after filter returns, UserContextHolder is still null
        assertNull(UserContextHolder.getUserId());
    }
}
