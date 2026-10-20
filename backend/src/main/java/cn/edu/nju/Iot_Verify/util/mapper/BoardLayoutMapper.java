package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.board.BoardLayoutDto;
import cn.edu.nju.Iot_Verify.po.BoardLayoutPo;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class BoardLayoutMapper {

    private static final double DEFAULT_CONTROL_WIDTH = 320.0;
    private static final double DEFAULT_INSPECTOR_WIDTH = 320.0;
    private static final double DEFAULT_ZOOM = 1.0;
    private static final double MAX_ZOOM = 2.0;
    private static final double MAX_ABS_PAN = 1_000_000.0;
    private static final String DEFAULT_CONTROL_SECTION = "templates";
    private static final String DEFAULT_INSPECTOR_SECTION = "devices";
    private static final Set<String> CONTROL_SECTIONS = Set.of("devices", "templates", "rules", "specs");
    private static final Set<String> INSPECTOR_SECTIONS = Set.of("devices", "rules", "specs", "environment");

    private static String text(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    public BoardLayoutDto toDto(BoardLayoutPo po) {
        if (po == null) {
            return null;
        }
        BoardLayoutDto dto = new BoardLayoutDto();

        BoardLayoutDto.CanvasPan pan = new BoardLayoutDto.CanvasPan();
        pan.setX(pan(po.getCanvasPanX()));
        pan.setY(pan(po.getCanvasPanY()));
        dto.setCanvasPan(pan);

        dto.setCanvasZoom(zoom(po.getCanvasZoom()));

        BoardLayoutDto.Panels panels = new BoardLayoutDto.Panels();
        panels.setControl(panelLayout(
                po.getControlPanelCollapsed(),
                po.getControlPanelWidth(),
                po.getControlPanelActiveSection(),
                DEFAULT_CONTROL_WIDTH,
                DEFAULT_CONTROL_SECTION,
                CONTROL_SECTIONS));
        panels.setInspector(panelLayout(
                po.getInspectorPanelCollapsed(),
                po.getInspectorPanelWidth(),
                po.getInspectorPanelActiveSection(),
                DEFAULT_INSPECTOR_WIDTH,
                DEFAULT_INSPECTOR_SECTION,
                INSPECTOR_SECTIONS));
        dto.setPanels(panels);

        return dto;
    }

    public BoardLayoutPo toEntity(BoardLayoutDto layout, Long id, Long userId) {
        BoardLayoutDto.PanelLayout controlPanel = layout.getPanels() != null
                ? layout.getPanels().getControl()
                : null;
        BoardLayoutDto.PanelLayout inspectorPanel = layout.getPanels() != null
                ? layout.getPanels().getInspector()
                : null;

        return BoardLayoutPo.builder()
                .id(id)
                .userId(userId)
                .canvasPanX(layout.getCanvasPan() != null ? pan(layout.getCanvasPan().getX()) : 0.0)
                .canvasPanY(layout.getCanvasPan() != null ? pan(layout.getCanvasPan().getY()) : 0.0)
                .canvasZoom(zoom(layout.getCanvasZoom()))
                .controlPanelCollapsed(panelCollapsed(controlPanel))
                .controlPanelWidth(panelWidth(controlPanel, DEFAULT_CONTROL_WIDTH))
                .controlPanelActiveSection(panelActiveSection(controlPanel, DEFAULT_CONTROL_SECTION, CONTROL_SECTIONS))
                .inspectorPanelCollapsed(panelCollapsed(inspectorPanel))
                .inspectorPanelWidth(panelWidth(inspectorPanel, DEFAULT_INSPECTOR_WIDTH))
                .inspectorPanelActiveSection(panelActiveSection(inspectorPanel, DEFAULT_INSPECTOR_SECTION, INSPECTOR_SECTIONS))
                .build();
    }

    private BoardLayoutDto.PanelLayout panelLayout(Boolean collapsed,
                                                   Double width,
                                                   String activeSection,
                                                   double defaultWidth,
                                                   String defaultSection,
                                                   Set<String> allowedSections) {
        BoardLayoutDto.PanelLayout panel = new BoardLayoutDto.PanelLayout();
        panel.setCollapsed(Boolean.TRUE.equals(collapsed));
        panel.setWidth(panelWidth(width, defaultWidth));
        panel.setActiveSection(section(activeSection, defaultSection, allowedSections));
        return panel;
    }

    private boolean panelCollapsed(BoardLayoutDto.PanelLayout panel) {
        return panel != null && Boolean.TRUE.equals(panel.getCollapsed());
    }

    private double panelWidth(BoardLayoutDto.PanelLayout panel, double defaultWidth) {
        if (panel == null) {
            return defaultWidth;
        }
        return panelWidth(panel.getWidth(), defaultWidth);
    }

    private double panelWidth(Double width, double defaultWidth) {
        if (width == null || !Double.isFinite(width)) {
            return defaultWidth;
        }
        return Math.min(520.0, Math.max(240.0, width));
    }

    private double pan(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0.0;
        }
        return Math.min(MAX_ABS_PAN, Math.max(-MAX_ABS_PAN, value));
    }

    private double zoom(Double value) {
        if (value == null || !Double.isFinite(value) || value <= 0) {
            return DEFAULT_ZOOM;
        }
        return Math.min(MAX_ZOOM, value);
    }

    private String panelActiveSection(BoardLayoutDto.PanelLayout panel, String defaultSection, Set<String> allowedSections) {
        return panel != null ? section(panel.getActiveSection(), defaultSection, allowedSections) : defaultSection;
    }

    private String section(String value, String defaultSection, Set<String> allowedSections) {
        String normalized = text(value, defaultSection);
        return allowedSections.contains(normalized) ? normalized : defaultSection;
    }
}
