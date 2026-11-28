package cn.edu.nju.Iot_Verify.service.board;

import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.po.BoardEnvironmentVariablePo;
import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.po.RulePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.*;
import cn.edu.nju.Iot_Verify.service.impl.BoardStorageServiceImpl;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

final class BoardEditUndoFixtures {

    private BoardEditUndoFixtures() {
    }

    static BoardEditJournal newJournal(BoardEditJournalRepository journalRepo) {
        return new BoardEditJournal(journalRepo, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    static BoardStorageServiceImpl boardService(
            DeviceNodeRepository nodes, BoardEnvironmentVariableRepository environments,
            SpecificationRepository specs, RuleRepository rules, DeviceTemplateRepository templates,
            UserRepository users, PlatformTransactionManager transactionManager, BoardEditJournal journal) {
        return new BoardStorageServiceImpl(
                nodes, environments, specs, rules, null, templates, null,
                new TransactionTemplate(transactionManager), null,
                new SpecificationMapper(), new RuleMapper(), new DeviceNodeMapper(), null,
                new DeviceTemplateMapper(), null, users, journal);
    }

    static Long createUser(UserRepository users, String phone, String username) {
        UserPo user = new UserPo();
        user.setPhone(phone);
        user.setUsername(username);
        user.setPassword("hashed");
        user.setCreatedAt(LocalDateTime.now());
        return users.saveAndFlush(user).getId();
    }

    /**
     * A rule needs a device to reference, and a device needs its template manifest. The shipped
     * Light manifest is reused so this fixture cannot drift from the real validation rules.
     */
    static void seedLightBoard(DeviceTemplateRepository templates, DeviceNodeRepository nodes,
                               BoardEnvironmentVariableRepository environments, Long owner) {
        String manifest;
        try (var stream = BoardEditUndoFixtures.class.getResourceAsStream("/deviceTemplate/Light.json")) {
            manifest = new String(Objects.requireNonNull(stream).readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Light device template fixture is unavailable", e);
        }
        templates.saveAndFlush(DeviceTemplatePo.builder()
                .userId(owner)
                .name("Light")
                .defaultTemplate(true)
                .manifestJson(manifest)
                .build());
        nodes.saveAndFlush(DeviceNodePo.builder()
                .id("light-1")
                .userId(owner)
                .templateName("Light")
                .label("Light 1")
                .posX(0.0).posY(0.0).state("on").width(176).height(128)
                .build());
        environments.saveAndFlush(BoardEnvironmentVariablePo.builder()
                .userId(owner)
                .name("illuminance")
                .value("10")
                .trust("untrusted")
                .privacy("public")
                .build());
    }

    /** A minimal rule that passes board validation against the Light template. */
    static RuleDto newRule(String text) {
        return newRule(text, "on", "off");
    }

    /**
     * A rule distinguished by its trigger and action, not just its text: the board rejects two
     * semantically identical rules, so tests holding several rules at once must vary these.
     */
    static RuleDto newRule(String text, String triggerState, String action) {
        RuleDto.Condition trigger = new RuleDto.Condition();
        trigger.setDeviceName("light-1");
        trigger.setTargetType("state");
        trigger.setAttribute("state");
        trigger.setRelation("=");
        trigger.setValue(triggerState);

        RuleDto.Command command = new RuleDto.Command();
        command.setDeviceName("light-1");
        command.setAction(action);

        RuleDto rule = new RuleDto();
        rule.setConditions(List.of(trigger));
        rule.setCommand(command);
        rule.setRuleString(text);
        return rule;
    }

    static void persistHistoricRuleTimestamp(RuleRepository rules, EntityManager entityManager, Long ruleId) {
        RulePo stored = rules.findById(ruleId).orElseThrow();
        stored.setCreatedAt(LocalDateTime.of(2001, 2, 3, 4, 5, 6));
        rules.saveAndFlush(stored);
        entityManager.clear();
    }
}
