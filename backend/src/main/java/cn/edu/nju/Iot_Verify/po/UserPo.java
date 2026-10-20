package cn.edu.nju.Iot_Verify.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "app_user")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserPo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String phone;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    @ToString.Exclude
    private String password;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "auth_version", nullable = false, columnDefinition = "bigint default 0")
    @Builder.Default
    @JsonIgnore
    @ToString.Exclude
    private Long authVersion = 0L;

    @Column(name = "formal_operation_fence", nullable = false)
    @Builder.Default
    @JsonIgnore
    private Long formalOperationFence = 0L;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (formalOperationFence == null) formalOperationFence = 0L;
        if (authVersion == null) authVersion = 0L;
    }
}
