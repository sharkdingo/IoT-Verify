// src/main/java/cn/edu/nju/Iot_Verify/po/SpecificationPo.java
package cn.edu.nju.Iot_Verify.po;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "specification", indexes = {
        @Index(name = "idx_specification_user_order", columnList = "user_id, list_order")
})
@IdClass(SpecificationId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SpecificationPo {

    @Id
    @Column(length = 100)
    @EqualsAndHashCode.Include
    private String id;

    @Id
    @Column(name = "user_id", nullable = false)
    @EqualsAndHashCode.Include
    private Long userId;

    /** Internal persistence of the user-visible specification list order. */
    @Column(name = "list_order", nullable = false)
    private Integer listOrder;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "template_id", nullable = false, length = 10)
    private String templateId;

    @Column(name = "template_label", nullable = false, length = 255)
    private String templateLabel;

    @Column(name = "a_conditions", columnDefinition = "JSON", nullable = false)
    private String aConditionsJson;

    @Column(name = "if_conditions", columnDefinition = "JSON", nullable = false)
    private String ifConditionsJson;

    @Column(name = "then_conditions", columnDefinition = "JSON", nullable = false)
    private String thenConditionsJson;

    @Column(name = "formula", columnDefinition = "TEXT")
    private String formula;

    @Column(name = "devices_json", columnDefinition = "JSON")
    private String devicesJson;
}
