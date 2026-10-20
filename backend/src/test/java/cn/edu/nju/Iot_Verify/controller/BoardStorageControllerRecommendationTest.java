package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.component.board.BoardBatchRequestParser;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneRequestParser;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.recommendation.*;
import cn.edu.nju.Iot_Verify.dto.rule.DuplicateRuleCheckResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleSimilarityResultDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.*;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.InteractiveAiExecutionService;
import cn.edu.nju.Iot_Verify.service.recommendation.*;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BoardStorageControllerRecommendationTest {
    @Mock private BoardStorageService boardService;
    @Mock private RuleRecommendationService rules;
    @Mock private DeviceRecommendationService devices;
    @Mock private SpecificationRecommendationService specifications;
    @Mock private ScenarioRecommendationService scenarios;
    @Mock private RuleCheckService ruleChecks;
    @Mock private InteractiveAiExecutionService executions;
    @Mock private DeviceTemplateSchemaValidator schemaValidator;
    @Mock private BoardBatchRequestParser batchParser;
    @Mock private PortableSceneRequestParser sceneParser;
    private BoardStorageController controller;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        controller = new BoardStorageController(boardService, new BoardDataConverter(boardService),
                new ModelFingerprint(mapper), rules, devices, specifications, scenarios, ruleChecks,
                mapper, schemaValidator, batchParser, sceneParser, executions);
        lenient().when(executions.execute(anyLong(), anyString(), any()))
                .thenAnswer(invocation -> invocation.<Callable<?>>getArgument(2).call());
    }

    @Test
    void removeRulePassesConfirmedSnapshotToLockedMutation() {
        RuleDto expected = new RuleDto();
        expected.setId(7L);
        controller.removeRule(1L, 7L, expected);
        verify(boardService).removeRuleIfUnchanged(1L, 7L, expected);
    }

    @Test
    void removeSpecPassesConfirmedSnapshotToLockedMutation() {
        SpecificationDto expected = new SpecificationDto();
        expected.setId("spec-7");
        controller.removeSpec(1L, "spec-7", expected);
        verify(boardService).removeSpecIfUnchanged(1L, "spec-7", expected);
    }

    @Test
    void rulesUseExplicitOwnerAndReturnTheApplicationResult() {
        var response = new RecommendationResponseDto<RuleRecommendationDto>();
        when(rules.recommend(37L, 3, "zh-CN", "save energy")).thenReturn(response);
        assertSame(response, controller.recommendRules(37L, request()).getData());
        verify(executions).execute(eq(37L), eq("request-123"), any());
        verify(rules).recommend(37L, 3, "zh-CN", "save energy");
    }

    @Test
    void specificationsUseExplicitOwnerAndReturnTheApplicationResult() {
        var response = new RecommendationResponseDto<SpecificationRecommendationDto>();
        when(specifications.recommend(37L, 3, "zh-CN", "save energy")).thenReturn(response);
        assertSame(response, controller.recommendSpecs(37L, request()).getData());
        verify(executions).execute(eq(37L), eq("request-123"), any());
    }

    @Test
    void deviceOptionalOptionsReachTheApplicationAsAbsentValues() {
        var response = new RecommendationResponseDto<DeviceRecommendationDto>();
        when(devices.recommend(37L, null, null, null)).thenReturn(response);
        assertSame(response, controller.recommendDevices(
                37L, "request-123", new DeviceRecommendationRequestDto()).getData());
        verify(executions).execute(eq(37L), eq("request-123"), any());
    }

    @Test
    void scenarioRestUsesTheDraftFreeApplicationEntry() {
        var request = new ScenarioRecommendationRequestDto();
        var response = new ScenarioRecommendationResponseDto();
        when(scenarios.recommend(37L, request)).thenReturn(response);
        assertSame(response, controller.recommendScenario(37L, "request-123", request).getData());
        verify(scenarios).recommend(37L, request);
        verifyNoMoreInteractions(scenarios);
    }

    @Test
    void duplicateCheckUsesTheOriginalTypedRule() {
        RuleDto candidate = new RuleDto();
        var response = DuplicateRuleCheckResultDto.builder().build();
        when(ruleChecks.checkDuplicate(37L, candidate)).thenReturn(response);
        assertSame(response, controller.checkDuplicateRule(37L, candidate).getData());
        verify(ruleChecks).checkDuplicate(37L, candidate);
    }

    @Test
    void similarityCheckUsesTheOriginalTypedRule() {
        RuleDto candidate = new RuleDto();
        var response = RuleSimilarityResultDto.builder().build();
        when(ruleChecks.checkSimilarity(37L, candidate)).thenReturn(response);
        assertSame(response, controller.checkRuleSimilarity(37L, candidate).getData());
        verify(ruleChecks).checkSimilarity(37L, candidate);
    }

    @ParameterizedTest
    @MethodSource("typedFailures")
    void preservesTypedFailureIdentityAndStatus(BaseException failure) {
        RuleDto candidate = new RuleDto();
        when(ruleChecks.checkDuplicate(37L, candidate)).thenThrow(failure);
        assertSame(failure, assertThrows(BaseException.class,
                () -> controller.checkDuplicateRule(37L, candidate)));
    }

    private static Stream<BaseException> typedFailures() {
        return Stream.of(new BadRequestException("bad input"), new UnauthorizedException("expired"),
                new ForbiddenException("forbidden"), new ResourceNotFoundException("missing"),
                new ConflictException("conflict"), new ValidationException("invalid"),
                new BadGatewayException("malformed provider result"),
                new ServiceUnavailableException("busy"), new InternalServerException("failed"));
    }

    private static StandaloneRecommendationRequestDto request() {
        var request = new StandaloneRecommendationRequestDto();
        request.setRequestId("request-123");
        request.setMaxRecommendations(3);
        request.setLanguage("zh-CN");
        request.setUserRequirement("save energy");
        return request;
    }
}
