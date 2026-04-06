package net.javahippie.fitpub.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "peaks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Peak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "osm_id", nullable = false, unique = true)
    private Long osmId;

    @Column(nullable = false)
    private String name;

    @Column(length = 500)
    private String wikipedia;

    @Column(length = 50)
    private String wikidata;

    @Column(columnDefinition = "geometry(Point, 4326)", nullable = false)
    private Point geom;
}
