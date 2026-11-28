package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.po.UserPo;

import java.util.Optional;

public interface UserService {
    UserPo register(String phone, String username, String password);
    Optional<UserPo> findByPhone(String phone);
    Optional<UserPo> findByUsername(String username);
}
