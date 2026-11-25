// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.spotify;

/** MU-10: hourly add budget exhausted; stop the job instead of provoking flood control. */
public final class ImportBudgetExceededException extends Exception {
    public ImportBudgetExceededException(int limit) {
        super("Import add budget exhausted: " + limit + "/hour");
    }
}
