// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import java.util.List;
import java.util.Objects;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;

/**
 * One archived device file, offered to every sink alike.
 *
 * <p>The batch carries both granularities because the sinks consume different ones: OPPO Health is
 * fed parsed measurements, while Mi Health ingests the original activity file and parses it with its
 * own pipeline (which is also what makes its cloud upload work). Keeping both in one object means a
 * single read of {@code RawFitnessFileStore} serves every target and the two cannot drift apart.
 */
public final class HealthBatch {
    /** Stable identity of the source file; also the dedupe key on the Mi Health side. */
    public final String fileHash;
    /** Xiaomi device id (did) exactly as bound in Mi Health. */
    public final String deviceId;
    public final String firmware;
    public final long capturedAtMs;
    /** Raw activity file bytes, straight from {@code RawFitnessFileStore}. */
    public final byte[] payload;
    /** Parsed rows for this file, in parser order. */
    public final List<HealthRecord> measurements;
    /** Index of the first measurement not yet delivered, i.e. the sink's resume point. */
    public final int fromIndex;

    public HealthBatch(String fileHash, String deviceId, String firmware, long capturedAtMs,
            byte[] payload, List<HealthRecord> measurements, int fromIndex) {
        this.fileHash = Objects.requireNonNull(fileHash);
        this.deviceId = deviceId == null ? "" : deviceId;
        this.firmware = firmware == null ? "" : firmware;
        this.capturedAtMs = capturedAtMs;
        this.payload = payload;
        this.measurements = measurements == null ? List.of() : List.copyOf(measurements);
        this.fromIndex = Math.max(0, fromIndex);
    }

    /** Rows still to be delivered, without copying the list. */
    public List<HealthRecord> pending() {
        if (fromIndex >= measurements.size()) return List.of();
        return measurements.subList(fromIndex, measurements.size());
    }

    /** An identity that is safe to print in a diagnostic log. */
    public String describe() {
        return "file=" + fileHash.substring(0, Math.min(8, fileHash.length()))
                + " device=" + (deviceId.length() > 4 ? deviceId.substring(deviceId.length() - 4) : deviceId)
                + " fw=" + firmware + " rows=" + measurements.size() + " from=" + fromIndex;
    }
}
