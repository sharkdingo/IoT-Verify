package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.UserPo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@H2RepositoryTest
class UserRepositoryTest {

    @Autowired
    private UserRepository repository;

    @Test
    void save_shouldUseNonReservedPhysicalTableName() {
        UserPo saved = repository.save(Objects.requireNonNull(UserPo.builder()
                .phone("13800000000")
                .username("schema-user")
                .password("encoded-password")
                .build()));

        assertTrue(saved.getId() > 0);
        assertEquals("schema-user", repository.findByPhone("13800000000").orElseThrow().getUsername());
    }
}
