package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @Test
    void register_shouldPersistCanonicalUsername() {
        UserServiceImpl service = new UserServiceImpl(userRepository, passwordEncoder);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(UserPo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserPo user = service.register("13800138000", "  Alice  ", "password123");

        assertEquals("Alice", user.getUsername());
        verify(userRepository).existsByUsername("Alice");
    }

    @Test
    void register_shouldRevalidateLengthAfterTrimming() {
        UserServiceImpl service = new UserServiceImpl(userRepository, passwordEncoder);

        assertThrows(BadRequestException.class,
                () -> service.register("13800138000", "  a  ", "password123"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_shouldRejectControlCharacters() {
        UserServiceImpl service = new UserServiceImpl(userRepository, passwordEncoder);

        assertThrows(BadRequestException.class,
                () -> service.register("13800138000", "Ali\nce", "password123"));
    }

    @Test
    void register_shouldRejectPhoneShapedUsername() {
        UserServiceImpl service = new UserServiceImpl(userRepository, passwordEncoder);

        assertThrows(BadRequestException.class,
                () -> service.register("13900139000", "13800138000", "password123"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void registrationUsesEachIdentifiersOwnNamespaceDespitePhoneCollationEquivalence() {
        UserServiceImpl service = new UserServiceImpl(userRepository, passwordEncoder);
        String phone = "13900139000";
        String fullwidthUsername = "\uff11\uff13\uff18\uff10\uff10\uff11\uff13\uff18\uff10\uff10\uff10";
        // MySQL's phone collation considers this name equal to a different user's ASCII phone.
        // It is still a username under the shared lexical classifier, never a phone login alias.
        lenient().when(userRepository.existsByPhone(fullwidthUsername)).thenReturn(true);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(UserPo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserPo user = assertDoesNotThrow(() -> service.register(phone, fullwidthUsername, "password123"));

        assertEquals(fullwidthUsername, user.getUsername());
        verify(userRepository).existsByPhone(phone);
        verify(userRepository).existsByUsername(fullwidthUsername);
        verify(userRepository, never()).existsByPhone(fullwidthUsername);
        verify(userRepository, never()).existsByUsername(phone);
    }

    @Test
    void registrationStillRejectsDuplicatePhonesInThePhoneNamespace() {
        UserServiceImpl service = new UserServiceImpl(userRepository, passwordEncoder);
        when(userRepository.existsByPhone("13800138000")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.register("13800138000", "Alice", "password123"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void registrationStillRejectsDuplicateNamesInTheUsernameNamespace() {
        UserServiceImpl service = new UserServiceImpl(userRepository, passwordEncoder);
        when(userRepository.existsByUsername("Alice")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.register("13800138000", "Alice", "password123"));

        verify(userRepository, never()).save(any());
    }
}
