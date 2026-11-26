// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OHealthStepLoweringTest {
    private static OHealthStepLowering.Request armed(int stored, int target, long armedAtMs) {
        return new OHealthStepLowering.Request("account", "MAC", 20261126,
                OHealthStepWriter.DAY_STEP_MODE, stored, target, armedAtMs);
    }

    @Test public void onlyTheExactStoredRowIsClaimed() {
        var request = armed(6800, 6632, System.currentTimeMillis());
        assertTrue(request.matchesValues("account", "MAC", 20261126, OHealthStepWriter.DAY_STEP_MODE, 6800));
        // A merge of any other row must be left alone, or the correction lands on someone else's
        // number: another account, another device, another day, another mode, another total.
        assertFalse(request.matchesValues("other", "MAC", 20261126, OHealthStepWriter.DAY_STEP_MODE, 6800));
        assertFalse(request.matchesValues("account", "OTHER", 20261126, OHealthStepWriter.DAY_STEP_MODE, 6800));
        assertFalse(request.matchesValues("account", "MAC", 20261127, OHealthStepWriter.DAY_STEP_MODE, 6800));
        assertFalse(request.matchesValues("account", "MAC", 20261126, -2, 6800));
        assertFalse(request.matchesValues("account", "MAC", 20261126, OHealthStepWriter.DAY_STEP_MODE, 6632));
    }

    @Test public void theBandTotalMustBeLowerToCountAsACorrection() {
        assertFalse(armed(6632, 6632, System.currentTimeMillis())
                .matchesValues("account", "MAC", 20261126, OHealthStepWriter.DAY_STEP_MODE, 6632));
        assertFalse(armed(6632, 6800, System.currentTimeMillis())
                .matchesValues("account", "MAC", 20261126, OHealthStepWriter.DAY_STEP_MODE, 6632));
        assertTrue(armed(6800, 6632, System.currentTimeMillis())
                .matchesValues("account", "MAC", 20261126, OHealthStepWriter.DAY_STEP_MODE, 6800));
    }

    @Test public void aRequestIsOnlyHonouredWhileFresh() {
        assertFalse(armed(6800, 6632, System.currentTimeMillis()).expired());
        assertTrue(armed(6800, 6632, System.currentTimeMillis() - 60_000L).expired());
    }

    @Test public void aRequestSurvivesTheTripThroughItsFile() throws Exception {
        // The writer and the merge live in different processes, so the request is only ever seen
        // as this JSON. A field lost here silently disarms every correction.
        var request = armed(6800, 6632, 1_765_000_000_000L);
        var restored = OHealthStepLowering.Request.fromJson(request.toJson());
        assertEquals("account", restored.account);
        assertEquals("MAC", restored.device);
        assertEquals(20261126, restored.date);
        assertEquals(OHealthStepWriter.DAY_STEP_MODE, restored.mode);
        assertEquals(6800, restored.previous);
        assertEquals(6632, restored.steps);
        assertEquals(1_765_000_000_000L, restored.armedAtMs);
        assertTrue(restored.matchesValues("account", "MAC", 20261126,
                OHealthStepWriter.DAY_STEP_MODE, 6800));
    }
}
