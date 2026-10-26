package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.SpecificationPo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class SpecificationRepositoryTest {

    @Autowired
    private SpecificationRepository repository;

    @Test
    void save_shouldAllowSameSpecificationIdAcrossDifferentUsers() {
        repository.saveAndFlush(Objects.requireNonNull(specification("shared-spec", 1L, "User one")));
        repository.saveAndFlush(Objects.requireNonNull(specification("shared-spec", 2L, "User two")));

        List<SpecificationPo> user1 = repository.findByUserId(1L);
        List<SpecificationPo> user2 = repository.findByUserId(2L);

        assertEquals(1, user1.size());
        assertEquals(1, user2.size());
        assertEquals("shared-spec", user1.get(0).getId());
        assertEquals("User one", user1.get(0).getTemplateLabel());
        assertEquals("shared-spec", user2.get(0).getId());
        assertEquals("User two", user2.get(0).getTemplateLabel());
    }

    private SpecificationPo specification(String id, Long userId, String label) {
        return SpecificationPo.builder().name("Safety requirement")
                .id(id)
                .userId(userId)
                .listOrder(0)
                .templateId("1")
                .templateLabel(label)
                .aConditionsJson("[]")
                .ifConditionsJson("[]")
                .thenConditionsJson("[]")
                .devicesJson("[]")
                .build();
    }
}
