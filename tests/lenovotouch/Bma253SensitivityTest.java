// SPDX-License-Identifier: MIT
package lenovotouch;

public final class Bma253SensitivityTest {
    public static void main(String[] args) {
        // Levels 1..10 should strictly decrease threshold in mg (1 = firmest, 10 = lightest).
        int prevMg = Integer.MAX_VALUE;
        for (int i = Bma253Sensitivity.MIN_LEVEL; i <= Bma253Sensitivity.MAX_LEVEL; i++) {
            Bma253Sensitivity.Level lv = Bma253Sensitivity.forLevel(i);
            assert lv.level == i;
            assert lv.thresholdMg < prevMg : "level " + i + " threshold not decreasing";
            int computed = Bma253Sensitivity.thresholdMgFromRegisters(lv.rangeReg, lv.int9Reg);
            assert computed == lv.thresholdMg : "mismatch at level " + i + ": " + computed + " vs " + lv.thresholdMg;
            prevMg = lv.thresholdMg;
        }

        // Level 7 is exact OEM stock (375 mg, range=0x05, int9=0x03).
        Bma253Sensitivity.Level stock = Bma253Sensitivity.forLevel(Bma253Sensitivity.DEFAULT_LEVEL);
        assert stock.isStock();
        assert stock.rangeReg == 0x05 && stock.int9Reg == 0x03 && stock.thresholdMg == 375;

        // Clamping out-of-range values.
        assert Bma253Sensitivity.forLevel(-5).level == 1;
        assert Bma253Sensitivity.forLevel(99).level == 10;
    }
}
