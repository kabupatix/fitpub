package net.javahippie.fitpub.util;

import com.garmin.fit.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone analysis of a FIT file's elevation data.
 * Compares session summary vs track point calculation.
 */
public class FitElevationAnalysis {

    @org.junit.jupiter.api.Test
    public void analyzeElevation() {
        analyze(new String[]{});
    }

    public static void analyze(String[] args) {
        try (InputStream is = FitElevationAnalysis.class.getResourceAsStream("/69287079d5e0a4532ba818ee.fit")) {
            if (is == null) {
                System.out.println("File not found on classpath");
                return;
            }

            Decode decode = new Decode();
            MesgBroadcaster broadcaster = new MesgBroadcaster(decode);

            // Collect session data
            final Integer[] sessionAscent = {null};
            final Integer[] sessionDescent = {null};

            // Collect track point elevations
            List<Double> elevations = new ArrayList<>();

            broadcaster.addListener((SessionMesgListener) session -> {
                sessionAscent[0] = session.getTotalAscent();
                sessionDescent[0] = session.getTotalDescent();
                System.out.println("=== SESSION DATA ===");
                System.out.println("Total Ascent (raw):  " + session.getTotalAscent() + " (type: " +
                    (session.getTotalAscent() != null ? session.getTotalAscent().getClass().getSimpleName() : "null") + ")");
                System.out.println("Total Descent (raw): " + session.getTotalDescent() + " (type: " +
                    (session.getTotalDescent() != null ? session.getTotalDescent().getClass().getSimpleName() : "null") + ")");
                System.out.println("Sport: " + session.getSport());
                System.out.println("Sub Sport: " + session.getSubSport());
            });

            broadcaster.addListener((RecordMesgListener) record -> {
                if (record.getAltitude() != null) {
                    elevations.add(record.getAltitude().doubleValue());
                }
            });

            decode.read(is, broadcaster);

            System.out.println("\n=== TRACK POINT ELEVATION ANALYSIS ===");
            System.out.println("Total track points with elevation: " + elevations.size());

            if (elevations.isEmpty()) {
                System.out.println("No elevation data in track points!");
                return;
            }

            System.out.println("First elevation: " + elevations.get(0) + "m");
            System.out.println("Last elevation:  " + elevations.get(elevations.size() - 1) + "m");

            // Find min/max
            double min = elevations.stream().mapToDouble(d -> d).min().orElse(0);
            double max = elevations.stream().mapToDouble(d -> d).max().orElse(0);
            System.out.println("Min elevation: " + min + "m");
            System.out.println("Max elevation: " + max + "m");

            // Calculate gain/loss with different thresholds
            for (double threshold : new double[]{0, 1.0, 2.0, 3.0, 5.0}) {
                double gain = 0, loss = 0;
                for (int i = 1; i < elevations.size(); i++) {
                    double delta = elevations.get(i) - elevations.get(i - 1);
                    if (Math.abs(delta) > threshold) {
                        if (delta > 0) gain += delta;
                        else loss += Math.abs(delta);
                    }
                }
                System.out.printf("Threshold %.1fm: gain=%.1fm, loss=%.1fm%n", threshold, gain, loss);
            }

            // Show first 20 elevation deltas to check for anomalies
            System.out.println("\n=== FIRST 20 ELEVATION CHANGES ===");
            for (int i = 1; i < Math.min(21, elevations.size()); i++) {
                double delta = elevations.get(i) - elevations.get(i - 1);
                System.out.printf("  Point %d: %.2fm -> %.2fm (delta: %+.2fm)%n",
                    i, elevations.get(i - 1), elevations.get(i), delta);
            }

            System.out.println("\n=== COMPARISON ===");
            System.out.println("Session ascent:  " + sessionAscent[0] + "m");
            System.out.println("Session descent: " + sessionDescent[0] + "m");
            double gain2m = 0;
            for (int i = 1; i < elevations.size(); i++) {
                double delta = elevations.get(i) - elevations.get(i - 1);
                if (delta > 2.0) gain2m += delta;
            }
            System.out.printf("Calculated gain (2m threshold): %.1fm%n", gain2m);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
