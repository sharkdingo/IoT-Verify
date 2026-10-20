package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.board.BoardLayoutDto;
import cn.edu.nju.Iot_Verify.po.BoardLayoutPo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardLayoutMapperTest {

    private final BoardLayoutMapper mapper = new BoardLayoutMapper();

    @Test
    void toDto_addsPanelLayoutDefaults() {
        BoardLayoutPo po = BoardLayoutPo.builder()
                .id(1L)
                .userId(7L)
                .canvasPanX(12.0)
                .canvasPanY(34.0)
                .canvasZoom(1.25)
                .build();

        BoardLayoutDto dto = mapper.toDto(po);

        assertEquals(12.0, dto.getCanvasPan().getX());
        assertEquals(1.25, dto.getCanvasZoom());
        assertFalse(dto.getPanels().getControl().getCollapsed());
        assertEquals(320.0, dto.getPanels().getControl().getWidth());
        assertEquals("templates", dto.getPanels().getControl().getActiveSection());
        assertFalse(dto.getPanels().getInspector().getCollapsed());
        assertEquals(320.0, dto.getPanels().getInspector().getWidth());
        assertEquals("devices", dto.getPanels().getInspector().getActiveSection());
    }

    @Test
    void toDto_normalizesPersistedLayoutBounds() {
        BoardLayoutPo po = BoardLayoutPo.builder()
                .id(1L)
                .userId(7L)
                .canvasPanX(Double.MAX_VALUE)
                .canvasPanY(Double.NaN)
                .controlPanelWidth(100.0)
                .inspectorPanelWidth(900.0)
                .canvasZoom(99.0)
                .build();

        BoardLayoutDto dto = mapper.toDto(po);

        assertEquals(1_000_000.0, dto.getCanvasPan().getX());
        assertEquals(0.0, dto.getCanvasPan().getY());
        assertEquals(240.0, dto.getPanels().getControl().getWidth());
        assertEquals(520.0, dto.getPanels().getInspector().getWidth());
        assertEquals(2.0, dto.getCanvasZoom());
    }

    @Test
    void toEntity_persistsPanelLayoutAndClampsInvalidWidth() {
        BoardLayoutDto layout = new BoardLayoutDto();
        BoardLayoutDto.CanvasPan pan = new BoardLayoutDto.CanvasPan();
        pan.setX(10.0);
        pan.setY(20.0);
        layout.setCanvasPan(pan);
        layout.setCanvasZoom(0.8);

        BoardLayoutDto.Panels panels = new BoardLayoutDto.Panels();
        BoardLayoutDto.PanelLayout control = new BoardLayoutDto.PanelLayout();
        control.setCollapsed(true);
        control.setWidth(360.0);
        control.setActiveSection("rules");
        panels.setControl(control);

        BoardLayoutDto.PanelLayout inspector = new BoardLayoutDto.PanelLayout();
        inspector.setCollapsed(false);
        inspector.setWidth(900.0);
        inspector.setActiveSection("specs");
        panels.setInspector(inspector);
        layout.setPanels(panels);

        BoardLayoutPo po = mapper.toEntity(layout, 9L, 7L);

        assertEquals(9L, po.getId());
        assertEquals(7L, po.getUserId());
        assertTrue(po.getControlPanelCollapsed());
        assertEquals(360.0, po.getControlPanelWidth());
        assertEquals("rules", po.getControlPanelActiveSection());
        assertFalse(po.getInspectorPanelCollapsed());
        assertEquals(520.0, po.getInspectorPanelWidth());
        assertEquals("specs", po.getInspectorPanelActiveSection());
    }

    @Test
    void toEntity_clampsTooNarrowWidthAndFallsBackForInvalidWidth() {
        BoardLayoutDto layout = new BoardLayoutDto();
        layout.setCanvasZoom(Double.NaN);

        BoardLayoutDto.Panels panels = new BoardLayoutDto.Panels();
        BoardLayoutDto.PanelLayout control = new BoardLayoutDto.PanelLayout();
        control.setWidth(100.0); // below the 240 lower bound
        panels.setControl(control);

        BoardLayoutDto.PanelLayout inspector = new BoardLayoutDto.PanelLayout();
        inspector.setWidth(Double.NaN); // non-finite falls back to the default
        panels.setInspector(inspector);
        layout.setPanels(panels);

        BoardLayoutPo po = mapper.toEntity(layout, 9L, 7L);

        assertEquals(240.0, po.getControlPanelWidth());
        assertEquals(320.0, po.getInspectorPanelWidth());
        assertEquals(1.0, po.getCanvasZoom());
    }

    @Test
    void toEntity_preservesOverviewZoomAndNormalizesInvalidZoom() {
        BoardLayoutDto layout = new BoardLayoutDto();

        layout.setCanvasZoom(0.01);
        BoardLayoutPo overview = mapper.toEntity(layout, 9L, 7L);
        assertEquals(0.01, overview.getCanvasZoom());
        assertEquals(0.01, mapper.toDto(overview).getCanvasZoom());

        layout.setCanvasZoom(0.0);
        assertEquals(1.0, mapper.toEntity(layout, 9L, 7L).getCanvasZoom());

        layout.setCanvasZoom(-0.1);
        assertEquals(1.0, mapper.toEntity(layout, 9L, 7L).getCanvasZoom());

        layout.setCanvasZoom(9.0);
        assertEquals(2.0, mapper.toEntity(layout, 9L, 7L).getCanvasZoom());
    }

    @Test
    void toEntity_clampsCanvasPanBoundsAndRejectsNonFiniteValues() {
        BoardLayoutDto layout = new BoardLayoutDto();
        BoardLayoutDto.CanvasPan pan = new BoardLayoutDto.CanvasPan();
        pan.setX(-Double.MAX_VALUE);
        pan.setY(Double.POSITIVE_INFINITY);
        layout.setCanvasPan(pan);

        BoardLayoutPo po = mapper.toEntity(layout, 9L, 7L);

        assertEquals(-1_000_000.0, po.getCanvasPanX());
        assertEquals(0.0, po.getCanvasPanY());
    }

    @Test
    void environmentSection_roundTripsOnlyInInspector() {
        BoardLayoutDto layout = new BoardLayoutDto();
        BoardLayoutDto.Panels panels = new BoardLayoutDto.Panels();
        BoardLayoutDto.PanelLayout inspector = new BoardLayoutDto.PanelLayout();
        inspector.setActiveSection("environment");
        panels.setInspector(inspector);
        BoardLayoutDto.PanelLayout control = new BoardLayoutDto.PanelLayout();
        control.setActiveSection("environment");
        panels.setControl(control);
        layout.setPanels(panels);

        BoardLayoutPo po = mapper.toEntity(layout, 9L, 7L);

        assertEquals("environment", po.getInspectorPanelActiveSection());
        assertEquals("environment", mapper.toDto(po).getPanels().getInspector().getActiveSection());
        assertEquals("templates", po.getControlPanelActiveSection());
    }

    @Test
    void toEntity_rejectsUnknownActiveSections() {
        BoardLayoutDto layout = new BoardLayoutDto();
        BoardLayoutDto.Panels panels = new BoardLayoutDto.Panels();

        BoardLayoutDto.PanelLayout control = new BoardLayoutDto.PanelLayout();
        control.setActiveSection("unknown");
        panels.setControl(control);

        BoardLayoutDto.PanelLayout inspector = new BoardLayoutDto.PanelLayout();
        inspector.setActiveSection("overview");
        panels.setInspector(inspector);

        layout.setPanels(panels);

        BoardLayoutPo po = mapper.toEntity(layout, 9L, 7L);

        assertEquals("templates", po.getControlPanelActiveSection());
        assertEquals("devices", po.getInspectorPanelActiveSection());
    }
}
