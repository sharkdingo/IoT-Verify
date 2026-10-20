package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.service.UserService;
import cn.edu.nju.Iot_Verify.util.UsernameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserPo register(String phone, String username, String password) {
        String normalizedUsername = UsernameNormalizer.normalizeAndValidate(username);
        if (existsByPhone(phone)) {
            throw ConflictException.duplicatePhone(phone);
        }
        if (existsByUsername(normalizedUsername)) {
            throw ConflictException.duplicateUsername(normalizedUsername);
        }

        UserPo user = UserPo.builder()
                .phone(phone)
                .username(normalizedUsername)
                .password(passwordEncoder.encode(password))
                .createdAt(LocalDateTime.now())
                .build();

        return userRepository.save(Objects.requireNonNull(user, "user to save must not be null"));
    }

    @Override
    public Optional<UserPo> findByPhone(String phone) {
        return userRepository.findByPhone(phone);
    }

    @Override
    public Optional<UserPo> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public Optional<UserPo> findById(Long id) {
        return userRepository.findById(Objects.requireNonNull(id, "user id must not be null"));
    }

    @Override
    public boolean existsByPhone(String phone) {
        return userRepository.existsByPhone(phone);
    }

    @Override
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }
}
