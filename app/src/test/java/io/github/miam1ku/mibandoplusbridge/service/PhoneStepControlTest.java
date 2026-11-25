// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class PhoneStepControlTest {
    @Test public void revokingTheSensorPermissionIsTheOffSwitch() {
        assertEquals("pm revoke --user 0 com.heytap.health android.permission.ACTIVITY_RECOGNITION",
                PhoneStepControl.command(true));
    }

    @Test public void grantingItIsTheOnSwitch() {
        assertEquals("pm grant --user 0 com.heytap.health android.permission.ACTIVITY_RECOGNITION",
                PhoneStepControl.command(false));
    }

    @Test public void theTwoDirectionsNeverCollapse() {
        assertNotEquals(PhoneStepControl.command(true), PhoneStepControl.command(false));
    }

    /** {@code su -c sh -c <quoted>} is how OwnershipController runs every command. */
    @Test public void theCommandSurvivesSuDashCQuoting() {
        String quoted = OwnershipController.shellQuote(PhoneStepControl.command(true));
        assertTrue(quoted.startsWith("'"));
        assertTrue(quoted.endsWith("'"));
        assertTrue(quoted.contains("com.heytap.health"));
    }
}
