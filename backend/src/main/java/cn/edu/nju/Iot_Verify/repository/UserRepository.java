package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.UserPo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserPo, Long> {
    Optional<UserPo> findByPhone(String phone);
    Optional<UserPo> findByUsername(String username);
    boolean existsByPhone(String phone);
    boolean existsByUsername(String username);

    @Query("select u.sceneRevision from UserPo u where u.id = :id")
    Optional<Long> findSceneRevision(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserPo u where u.id = :id")
    Optional<UserPo> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from UserPo u where u.id = :id")
    Optional<UserPo> findByIdForShare(@Param("id") Long id);
}
