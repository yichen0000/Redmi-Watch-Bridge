// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A removed bond used to leave the session in a two minute loop with no repair. */
public final class BondRecoveryTest {
    @Test public void lostBondWakesTheWorkerAndRestoredBondWakesIt() {
        BondRecovery recovery = new BondRecovery();
        assertTrue("a removed bond is news", recovery.note(BondRecovery.NONE, 1_000));
        assertTrue("a restored bond is news", recovery.note(BondRecovery.BONDED, 2_000));
        assertFalse("pairing in progress changes nothing yet", recovery.note(BondRecovery.BONDING, 3_000));
    }

    @Test public void asksForTheBondOncePerLoss() {
        BondRecovery recovery = new BondRecovery();
        long now = 1_000;
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, now));
        recovery.asked(now);
        // The band confirm prompt lives on the user's wrist, so never nag automatically.
        assertEquals("no second automatic ask, however long the gap",
                BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, now + BondRecovery.ASK_GAP_MS));
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, now + 60 * 1_000));
        assertEquals("one request per loss", BondRecovery.MAX_ASKS, recovery.asks());
        assertTrue(recovery.gaveUp());
    }

    @Test public void pairingInProgressAndRestoredBondSkipTheRequest() {
        BondRecovery recovery = new BondRecovery();
        assertEquals(BondRecovery.WAIT, recovery.decide(BondRecovery.BONDING, 5_000));
        assertEquals(BondRecovery.READY, recovery.decide(BondRecovery.BONDED, 5_000));
        assertEquals("a restored bond spends no request", 0, recovery.asks());
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, 5_000));
        recovery.asked(5_000);
        // With a single ask the gap branch is unreachable: one request already spent the loss.
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 5_001));
    }

    @Test public void manualRetryRefillsTheBudgetImmediately() {
        BondRecovery recovery = new BondRecovery();
        recovery.asked(1_000);
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 100_000));
        recovery.manualRetry();
        assertEquals("the 重新配对 tap expects the next wrist prompt", BondRecovery.ASK,
                recovery.decide(BondRecovery.NONE, 100_000));
    }

    /**
     * The band closes every SPP session after five minutes, so a session that authenticates is not
     * an hour's worth of trust. Only the half hour since the last ask may refill the budget.
     */
    @Test public void aFiveMinuteSessionDoesNotRefillTheBudget() {
        BondRecovery recovery = new BondRecovery();
        long askedAt = 1_000;
        recovery.asked(askedAt);
        recovery.connected(askedAt + 5 * 60_000);
        assertEquals("the next session must not buy another wrist prompt",
                BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, askedAt + 5 * 60_000 + 1));
        recovery.note(BondRecovery.BONDED, askedAt + 6 * 60_000);
        assertEquals("a bond that came back is not a refill either",
                BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, askedAt + 6 * 60_000 + 1));
    }

    @Test public void halfAnHourAfterTheAskTheBudgetIsBack() {
        BondRecovery recovery = new BondRecovery();
        long askedAt = 1_000;
        recovery.asked(askedAt);
        recovery.connected(askedAt + BondRecovery.REFILL_GAP_MS);
        assertEquals(BondRecovery.ASK,
                recovery.decide(BondRecovery.NONE, askedAt + BondRecovery.REFILL_GAP_MS));
    }

    @Test public void noticeAsksForATapAfterTheOneAsk() {
        assertEquals("正在重新配对", BondRecovery.notice(false));
        assertEquals("手环需要重新配对，点通知重试", BondRecovery.notice(true));
    }

    /**
     * Clearing the app from recents restarts the process, and a budget that lived only in memory
     * came back full — so re-opening the app asked the wrist for a pairing the band had already
     * refused. The budget has to be restored, not reissued.
     */
    @Test public void aRestoredBudgetDoesNotAskAgain() {
        BondRecovery recovery = new BondRecovery();
        long askedAt = 10_000;
        recovery.asked(askedAt);
        assertEquals(askedAt, recovery.lastAskMs());

        BondRecovery afterRestart = new BondRecovery();
        afterRestart.restore(recovery.asks(), recovery.lastAskMs());
        assertEquals("a spent budget stays spent across a process restart",
                BondRecovery.GAVE_UP, afterRestart.decide(BondRecovery.NONE, askedAt + 60_000));
        afterRestart.note(BondRecovery.BONDED, askedAt + BondRecovery.REFILL_GAP_MS);
        assertEquals("and it still refills on the wall clock, not on the restart",
                BondRecovery.ASK,
                afterRestart.decide(BondRecovery.NONE, askedAt + BondRecovery.REFILL_GAP_MS));
    }

    @Test public void restoringNothingKeepsTheFullBudget() {
        BondRecovery recovery = new BondRecovery();
        recovery.restore(0, Long.MIN_VALUE);
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, 1_000));
    }
}
