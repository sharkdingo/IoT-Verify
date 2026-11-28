package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTemplateRepository extends JpaRepository<DeviceTemplatePo, Long> {
    List<DeviceTemplatePo> findByUserId(Long userId);
    Optional<DeviceTemplatePo> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);
    @Query("SELECT COUNT(t) > 0 FROM DeviceTemplatePo t WHERE t.userId = :userId AND LOWER(t.name) = LOWER(:name)")
    boolean existsByUserIdAndNameIgnoreCase(@Param("userId") Long userId, @Param("name") String name);

    void deleteByUserId(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM DeviceTemplatePo t
            WHERE t.userId = :userId
              AND (t.defaultTemplate = true OR LOWER(t.name) IN :defaultNames)
            """)
    int deleteDefaultsForReset(@Param("userId") Long userId, @Param("defaultNames") List<String> defaultNames);
}
