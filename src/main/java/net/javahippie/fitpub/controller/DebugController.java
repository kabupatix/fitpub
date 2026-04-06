package net.javahippie.fitpub.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * Debug controller for troubleshooting.
 */
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {

    private final UserRepository userRepository;
    private final net.javahippie.fitpub.service.PeakDetectionService peakDetectionService;
    private final net.javahippie.fitpub.service.ActivityFileService activityFileService;
    private final net.javahippie.fitpub.repository.ActivityRepository activityRepository;

    @GetMapping("/validate-keys")
    public Map<String, Object> validateKeys() {
        List<User> users = userRepository.findAll();
        Map<String, Object> results = new LinkedHashMap<>();

        for (User user : users) {
            Map<String, Object> userResult = new LinkedHashMap<>();

            try {
                // Parse public key
                PublicKey publicKey = parsePublicKey(user.getPublicKey());
                userResult.put("publicKeyValid", true);

                // Parse private key
                PrivateKey privateKey = parsePrivateKey(user.getPrivateKey());
                userResult.put("privateKeyValid", true);

                // Test if they match by signing and verifying
                String testData = "Test data for " + user.getUsername();
                byte[] signature = signData(testData.getBytes(StandardCharsets.UTF_8), privateKey);
                boolean verified = verifySignature(testData.getBytes(StandardCharsets.UTF_8), signature, publicKey);

                userResult.put("keysMatch", verified);
                userResult.put("publicKeyPem", user.getPublicKey());

                if (verified) {
                    log.info("✓ Key pair is valid for user: {}", user.getUsername());
                } else {
                    log.error("✗ Key pair MISMATCH for user: {}", user.getUsername());
                }

            } catch (Exception e) {
                userResult.put("error", e.getMessage());
                log.error("Failed to validate keys for user: {}", user.getUsername(), e);
            }

            results.put(user.getUsername(), userResult);
        }

        return results;
    }

    private PublicKey parsePublicKey(String publicKeyPem) throws Exception {
        String publicKeyContent = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(publicKeyContent);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }

    private PrivateKey parsePrivateKey(String privateKeyPem) throws Exception {
        String privateKeyContent = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(spec);
    }

    private byte[] signData(byte[] data, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }

    private boolean verifySignature(byte[] data, byte[] signatureBytes, PublicKey publicKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update(data);
        return signature.verify(signatureBytes);
    }

    @org.springframework.web.bind.annotation.PostMapping("/backfill-peaks")
    public org.springframework.http.ResponseEntity<Map<String, String>> backfillPeaks() {
        if (peakDetectionService.isBackfillRunning()) {
            return org.springframework.http.ResponseEntity.ok(Map.of("status", "already running"));
        }
        peakDetectionService.backfillAllActivities();
        return org.springframework.http.ResponseEntity.ok(Map.of("status", "started"));
    }

    private final java.util.concurrent.atomic.AtomicBoolean elevationReprocessRunning =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @org.springframework.web.bind.annotation.PostMapping("/reprocess-gpx-elevation")
    public org.springframework.http.ResponseEntity<Map<String, String>> reprocessGpxElevation() {
        if (!elevationReprocessRunning.compareAndSet(false, true)) {
            return org.springframework.http.ResponseEntity.ok(Map.of("status", "already running"));
        }

        new Thread(() -> {
            try {
                var ids = activityRepository.findAllIds();
                log.info("Starting GPX elevation reprocessing for {} activities", ids.size());

                int updated = 0, skipped = 0, failed = 0;
                for (int i = 0; i < ids.size(); i++) {
                    try {
                        var activity = activityRepository.findById(ids.get(i)).orElse(null);
                        if (activity == null || !"GPX".equals(activity.getSourceFileFormat())) {
                            skipped++;
                            continue;
                        }
                        if (activityFileService.reprocessGpxElevation(activity)) {
                            updated++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        failed++;
                        log.warn("Failed to reprocess elevation for {}: {}", ids.get(i), e.getMessage());
                    }
                    if ((i + 1) % 100 == 0) {
                        log.info("GPX elevation reprocess progress: {} / {} (updated={}, skipped={}, failed={})",
                            i + 1, ids.size(), updated, skipped, failed);
                    }
                }
                log.info("GPX elevation reprocessing complete: updated={}, skipped={}, failed={}", updated, skipped, failed);
            } finally {
                elevationReprocessRunning.set(false);
            }
        }, "gpx-elevation-reprocess").start();

        return org.springframework.http.ResponseEntity.ok(Map.of("status", "started"));
    }
}
