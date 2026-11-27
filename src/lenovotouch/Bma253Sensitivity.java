// SPDX-License-Identifier: MIT
package lenovotouch;

/**
 * Maps the user-facing 1..10 tap sensitivity scale to Bosch BMA253 register values.
 *
 * <p>BMA253 tap threshold (REG_INT_9 bits 4:0, tap_th) scales with REG_PMU_RANGE:
 * <ul>
 *   <li>0x03 (+/-2g): 62.5 mg per LSB (fine granularity for soft taps)</li>
 *   <li>0x05 (+/-4g): 125 mg per LSB (stock OEM Lenovo Smart Clock setting: range=0x05, int9=0x03 = 375 mg)</li>
 * </ul>
 */
public final class Bma253Sensitivity {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;
    public static final int DEFAULT_LEVEL = 7;

    public static final int RANGE_2G = 0x03;
    public static final int RANGE_4G = 0x05;

    public static final class Level {
        public final int level;
        public final int rangeReg;
        public final int int9Reg;
        public final int thresholdMg;
        public final String label;

        Level(int level, int rangeReg, int int9Reg, int thresholdMg, String label) {
            this.level = level;
            this.rangeReg = rangeReg;
            this.int9Reg = int9Reg;
            this.thresholdMg = thresholdMg;
            this.label = label;
        }

        public boolean isStock() {
            return rangeReg == RANGE_4G && int9Reg == 0x03;
        }

        public String summary() {
            return "Level " + level + " (" + label + ", " + thresholdMg + " mg)";
        }
    }

    // Index 0 unused; levels 1 (firmest) through 10 (lightest).
    private static final Level[] LEVELS = new Level[] {
        null,
        new Level(1,  RANGE_2G, 0x1A, 1625, "Very firm"),
        new Level(2,  RANGE_2G, 0x14, 1250, "Firm"),
        new Level(3,  RANGE_2G, 0x0F,  938, "Medium-firm"),
        new Level(4,  RANGE_2G, 0x0C,  750, "Moderate-firm"),
        new Level(5,  RANGE_2G, 0x0A,  625, "Medium"),
        new Level(6,  RANGE_2G, 0x08,  500, "Medium-light"),
        new Level(7,  RANGE_4G, 0x03,  375, "Stock"),
        new Level(8,  RANGE_2G, 0x04,  250, "Light"),
        new Level(9,  RANGE_2G, 0x03,  188, "Soft"),
        new Level(10, RANGE_2G, 0x02,  125, "Very soft"),
    };

    private Bma253Sensitivity() {}

    public static int clamp(int level) {
        if (level < MIN_LEVEL) return MIN_LEVEL;
        if (level > MAX_LEVEL) return MAX_LEVEL;
        return level;
    }

    public static Level forLevel(int level) {
        return LEVELS[clamp(level)];
    }

    public static int thresholdMgFromRegisters(int rangeReg, int int9Reg) {
        int tapTh = int9Reg & 0x1F;
        switch (rangeReg & 0x0F) {
            case 0x03: return Math.round(tapTh * 62.5f);
            case 0x05: return tapTh * 125;
            case 0x08: return tapTh * 250;
            case 0x0C: return tapTh * 500;
            default:   return -1;
        }
    }
}
