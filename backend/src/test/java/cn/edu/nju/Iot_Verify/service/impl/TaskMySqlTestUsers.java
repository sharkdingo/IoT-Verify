package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.UserRepository;

import java.util.UUID;

final class TaskMySqlTestUsers {
    private TaskMySqlTestUsers() {
    }

    static Long createOwner(UserRepository users) {
        String key = UUID.randomUUID().toString().replace("-", "");
        return users.saveAndFlush(UserPo.builder()
                .phone(key.substring(0, 20))
                .username("task-it-" + key)
                .password("unused")
                .build()).getId();
    }
}
