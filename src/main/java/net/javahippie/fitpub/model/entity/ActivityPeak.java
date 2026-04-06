package net.javahippie.fitpub.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "activity_peaks", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"activity_id", "peak_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityPeak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "peak_id", nullable = false)
    private Peak peak;
}
