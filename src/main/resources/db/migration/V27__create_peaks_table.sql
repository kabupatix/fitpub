CREATE TABLE peaks (
    id         SERIAL PRIMARY KEY,
    osm_id     BIGINT NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    wikipedia  VARCHAR(500),
    wikidata   VARCHAR(50),
    geom       geometry(Point, 4326) NOT NULL
);

CREATE INDEX idx_peaks_geom ON peaks USING GIST (geom);
CREATE INDEX idx_peaks_name ON peaks (name);
