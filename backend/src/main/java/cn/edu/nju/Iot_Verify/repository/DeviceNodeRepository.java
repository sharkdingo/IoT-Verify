package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.po.DeviceNodeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceNodeRepository extends JpaRepository<DeviceNodePo, DeviceNodeId> {
    List<DeviceNodePo> findByUserId(Long userId);
    boolean existsByUserIdAndId(Long userId, String id);

    void deleteByUserId(Long userId);
}
