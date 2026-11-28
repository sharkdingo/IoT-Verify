package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.util.TemplateNameRule;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.repository.DeviceTemplateRepository;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTemplateServiceImpl implements DeviceTemplateService {

    private final DeviceTemplateRepository templateRepo;
    private final DeviceTemplateSchemaValidator deviceTemplateSchemaValidator;
    private final ObjectMapper objectMapper;
    private volatile List<DefaultTemplateDefinition> cachedDefaultTemplateDefinitions;

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceTemplatePo> findTemplateByName(Long userId, String targetName) {
        if (targetName == null) {
            return Optional.empty();
        }
        String normalizedTarget = targetName.trim().toLowerCase(Locale.ROOT);
        return templateRepo.findByUserId(userId).stream()
                .filter(po -> po.getName() != null
                        && po.getName().trim().toLowerCase(Locale.ROOT).equals(normalizedTarget))
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean checkTemplateExists(Long userId, String targetName) {
        return findTemplateByName(userId, targetName).isPresent();
    }

    @Override
    @Transactional
    public int initDefaultTemplates(Long userId) {
        if (!templateRepo.findByUserId(userId).isEmpty()) {
            log.debug("User {} already has templates, skipping init", userId);
            return 0;
        }

        List<DeviceTemplatePo> templates = loadDefaultTemplateEntities(userId);
        templateRepo.saveAllAndFlush(templates);
        log.info("Imported {} default device templates for user {}", templates.size(), userId);
        return templates.size();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceTemplatePo> getDefaultTemplateDefinitions(Long userId) {
        return List.copyOf(loadDefaultTemplateEntities(userId));
    }

    private List<DeviceTemplatePo> loadDefaultTemplateEntities(Long userId) {
        return defaultTemplateDefinitions().stream()
                .map(definition -> DeviceTemplatePo.builder()
                        .userId(userId)
                        .name(definition.name())
                        .manifestJson(definition.manifestJson())
                        .defaultTemplate(true)
                        .build())
                .toList();
    }

    private List<DefaultTemplateDefinition> defaultTemplateDefinitions() {
        List<DefaultTemplateDefinition> cached = cachedDefaultTemplateDefinitions;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedDefaultTemplateDefinitions == null) {
                cachedDefaultTemplateDefinitions = loadAndValidateDefaultTemplateDefinitions();
            }
            return cachedDefaultTemplateDefinitions;
        }
    }

    private List<DefaultTemplateDefinition> loadAndValidateDefaultTemplateDefinitions() {
        List<DefaultTemplateDefinition> templates = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:deviceTemplate/*.json");
            log.info("Found {} bundled device template resources on classpath", resources.length);

            if (resources.length == 0) {
                resources = resolver.getResources("classpath*:deviceTemplate/*.json");
                log.info("Fallback found {} bundled device template resources", resources.length);
            }

            if (resources.length == 0) {
                throw new InternalServerException("No bundled default device templates are available.");
            }

            Arrays.sort(resources, java.util.Comparator.comparing(
                    resource -> Optional.ofNullable(resource.getFilename()).orElse("")));
            Set<String> normalizedNames = new HashSet<>();

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String filename = resource.getFilename();
                    JsonNode manifestNode = objectMapper.readTree(json);
                    String name = extractManifestName(manifestNode);
                    if (name == null || name.isBlank()) {
                        name = filename != null ? filename.replace(".json", "") : "Unknown";
                    }
                    if (name.isBlank()) {
                        name = "Unknown";
                    }

                    String nameRejection = TemplateNameRule.rejectionReason(name);
                    if (nameRejection != null) {
                        throw new InternalServerException("Bundled default template '" + name
                                + "' has an illegal name in " + resource.getFilename()
                                + ": " + nameRejection + ".");
                    }
                    String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                    if (!normalizedNames.add(normalizedName)) {
                        throw new InternalServerException("Bundled default template name is duplicated: " + name);
                    }
                    deviceTemplateSchemaValidator.validateRawManifest(name, manifestNode);

                    templates.add(new DefaultTemplateDefinition(name, json));
                } catch (Exception e) {
                    if (e instanceof InternalServerException internal) {
                        throw internal;
                    }
                    throw new InternalServerException("Bundled default template '"
                            + Optional.ofNullable(resource.getFilename()).orElse("unknown")
                            + "' is invalid; no defaults were imported.", e);
                }
            }
        } catch (Exception e) {
            if (e instanceof InternalServerException internal) {
                throw internal;
            }
            throw new InternalServerException("Failed to load bundled default device templates; no defaults were imported.", e);
        }
        return List.copyOf(templates);
    }

    private record DefaultTemplateDefinition(String name, String manifestJson) {
    }

    private String extractManifestName(JsonNode manifestNode) {
        DeviceManifest manifest = JsonUtils.fromJsonOrDefault(
                manifestNode.toString(),
                new TypeReference<DeviceManifest>() {},
                null
        );
        if (manifest == null || manifest.getName() == null) {
            return null;
        }
        return manifest.getName().trim();
    }
}
