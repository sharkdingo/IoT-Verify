package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class DeviceNodeRepositoryTest {

    @Autowired
    private DeviceNodeRepository repository;

    @Test
    void findByUserId_returnsOnlyTheOwnersNodes() {
        repository.save(Objects.requireNonNull(node("n1", 1L, "Smart Light", "Kitchen Lamp")));
        repository.save(Objects.requireNonNull(node("n2", 1L, "Thermostat", "Living Light Sensor")));
        repository.save(Objects.requireNonNull(node("n3", 2L, "Camera", "Guest Light")));

        List<DeviceNodePo> result = repository.findByUserId(1L);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(n -> n.getUserId().equals(1L)));
    }

    @Test
    void save_shouldAllowSameNodeIdAcrossDifferentUsers() {
        repository.save(Objects.requireNonNull(node("shared-node", 1L, "Smart Light", "Kitchen Lamp")));
        repository.save(Objects.requireNonNull(node("shared-node", 2L, "Smart Lock", "Door Lock")));

        List<DeviceNodePo> user1 = repository.findByUserId(1L);
        List<DeviceNodePo> user2 = repository.findByUserId(2L);

        assertEquals(1, user1.size());
        assertEquals(1, user2.size());
        assertEquals("shared-node", user1.get(0).getId());
        assertEquals("shared-node", user2.get(0).getId());
    }

    private DeviceNodePo node(String id, Long userId, String templateName, String label) {
        return DeviceNodePo.builder()
                .id(id)
                .userId(userId)
                .templateName(templateName)
                .label(label)
                .posX(0.0)
                .posY(0.0)
                .state("On")
                .width(110)
                .height(90)
                .build();
    }
}
