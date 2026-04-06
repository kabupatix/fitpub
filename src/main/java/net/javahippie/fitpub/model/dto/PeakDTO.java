package net.javahippie.fitpub.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.javahippie.fitpub.model.entity.Peak;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeakDTO {

    private Integer id;
    private String name;
    private String wikipedia;
    private Double latitude;
    private Double longitude;

    public static PeakDTO fromEntity(Peak peak) {
        return PeakDTO.builder()
            .id(peak.getId())
            .name(peak.getName())
            .wikipedia(peak.getWikipedia())
            .latitude(peak.getGeom() != null ? peak.getGeom().getY() : null)
            .longitude(peak.getGeom() != null ? peak.getGeom().getX() : null)
            .build();
    }
}
