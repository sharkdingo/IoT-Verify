package cn.edu.nju.Iot_Verify.testutil;

import cn.edu.nju.Iot_Verify.service.recommendation.DeviceInfoHelper;

import java.util.List;

public final class RecommendationToolFixtures {
    private RecommendationToolFixtures() {}

    public static DeviceInfoHelper.DeviceInfo homeModeDevice() {
        return new DeviceInfoHelper.DeviceInfo(
                "node-home-mode",
                "Home Mode",
                "Home Mode",
                "sleep;idle",
                "trusted",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new DeviceInfoHelper.ModeInfo("Mode", List.of("home", "away", "sleep")),
                        new DeviceInfoHelper.ModeInfo("State", List.of("idle", "sendingAlertMessage", "sendingPhoto"))),
                List.of("home;idle", "away;idle", "sleep;idle"),
                List.of()
        );
    }
}
