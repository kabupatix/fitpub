#!/usr/bin/env python3
"""
Import peaks from GeoJSON into PostgreSQL/PostGIS.

Usage:
    python3 import_peaks.py <geojson_file> [--db-url postgresql://user:pass@host:port/dbname]

The script parses the GeoJSON, extracts elevation/wikipedia/wikidata from the
OSM hstore-style other_tags field, and bulk-inserts into the peaks table using
COPY for performance (~1M rows).
"""

import argparse
import csv
import io
import json
import re
import sys

try:
    import psycopg2
except ImportError:
    print("psycopg2 is required: pip install psycopg2-binary", file=sys.stderr)
    sys.exit(1)


def parse_other_tags(other_tags: str) -> dict:
    """Parse OSM hstore-style other_tags string into a dict."""
    if not other_tags:
        return {}
    result = {}
    # Pattern: "key"=>"value"
    for match in re.finditer(r'"([^"]+)"=>"([^"]*)"', other_tags):
        result[match.group(1)] = match.group(2)
    return result


def wikipedia_to_url(wikipedia: str) -> str:
    """Convert 'en:Article Name' to full Wikipedia URL."""
    if not wikipedia:
        return None
    parts = wikipedia.split(":", 1)
    if len(parts) == 2:
        lang, title = parts
        return f"https://{lang}.wikipedia.org/wiki/{title.replace(' ', '_')}"
    return None


def main():
    parser = argparse.ArgumentParser(description="Import peaks GeoJSON into PostgreSQL")
    parser.add_argument("geojson_file", help="Path to the GeoJSON file")
    parser.add_argument("--db-url", default="postgresql://test:test@localhost:5432/testdb",
                        help="PostgreSQL connection URL")
    parser.add_argument("--batch-size", type=int, default=10000,
                        help="Batch size for COPY operations")
    args = parser.parse_args()

    print(f"Loading GeoJSON from {args.geojson_file}...")
    with open(args.geojson_file, "r") as f:
        data = json.load(f)

    features = data["features"]
    print(f"Loaded {len(features)} features")

    conn = psycopg2.connect(args.db_url)
    cur = conn.cursor()

    # Clear existing data
    cur.execute("TRUNCATE TABLE activity_peaks CASCADE")
    cur.execute("TRUNCATE TABLE peaks CASCADE")
    conn.commit()

    inserted = 0
    skipped = 0
    batch = []

    for feat in features:
        props = feat["properties"]
        geom = feat["geometry"]
        coords = geom["coordinates"]  # [lon, lat]

        name = props.get("name")
        if not name:
            skipped += 1
            continue

        osm_id = int(props["osm_id"])
        other = parse_other_tags(props.get("other_tags", ""))

        wikipedia = wikipedia_to_url(other.get("wikipedia"))
        wikidata = other.get("wikidata")

        lon, lat = coords[0], coords[1]

        batch.append((osm_id, name, wikipedia, wikidata, lon, lat))

        if len(batch) >= args.batch_size:
            _copy_batch(cur, batch)
            inserted += len(batch)
            batch = []
            print(f"  Inserted {inserted}...", end="\r")

    if batch:
        _copy_batch(cur, batch)
        inserted += len(batch)

    conn.commit()
    cur.close()
    conn.close()

    print(f"\nDone. Inserted {inserted} peaks, skipped {skipped} (no name).")


def _copy_batch(cur, batch):
    """Use COPY for fast bulk insert."""
    buf = io.StringIO()
    writer = csv.writer(buf, delimiter="\t")
    for osm_id, name, wikipedia, wikidata, lon, lat in batch:
        writer.writerow([
            osm_id,
            name,
            wikipedia if wikipedia else r"\N",
            wikidata if wikidata else r"\N",
            f"SRID=4326;POINT({lon} {lat})",
        ])
    buf.seek(0)
    cur.copy_expert(
        "COPY peaks (osm_id, name, wikipedia, wikidata, geom) FROM STDIN WITH (FORMAT csv, DELIMITER E'\\t', NULL '\\N')",
        buf,
    )


if __name__ == "__main__":
    main()
