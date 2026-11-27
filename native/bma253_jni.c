/* SPDX-License-Identifier: MIT
 * JNI helper for Bosch BMA253 tap-sensitivity register access over /dev/i2c-0 (0x18).
 *
 * Registers used:
 *   0x00 REG_BGW_CHIPID (expected 0xFA)
 *   0x0F REG_PMU_RANGE  (0x03 = +/-2g -> 62.5 mg/LSB, 0x05 = +/-4g -> 125 mg/LSB)
 *   0x2B REG_INT_9      (bits 7:6 tap_samp, bits 4:0 tap_th)
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/i2c-dev.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define BMA253_BUS_PATH "/dev/i2c-0"
#define BMA253_I2C_ADDR 0x18
#define BMA253_REG_CHIPID 0x00
#define BMA253_EXPECTED_CHIPID 0xFA
#define BMA253_REG_PMU_RANGE 0x0F
#define BMA253_REG_INT_9 0x2B

static int i2c_read_reg(int fd, uint8_t reg) {
    if (write(fd, &reg, 1) != 1) {
        return -errno;
    }
    uint8_t val = 0;
    if (read(fd, &val, 1) != 1) {
        return -errno;
    }
    return (int)val;
}

static int i2c_write_reg(int fd, uint8_t reg, uint8_t val) {
    uint8_t buf[2] = {reg, val};
    if (write(fd, buf, 2) != 2) {
        return -errno;
    }
    return 0;
}

static int open_bma253(void) {
    int fd = open(BMA253_BUS_PATH, O_RDWR);
    if (fd < 0) {
        return -errno;
    }
    if (ioctl(fd, I2C_SLAVE_FORCE, BMA253_I2C_ADDR) < 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    int chip = i2c_read_reg(fd, BMA253_REG_CHIPID);
    if (chip < 0) {
        close(fd);
        return chip;
    }
    if (chip != BMA253_EXPECTED_CHIPID) {
        close(fd);
        return -ENODEV;
    }
    return fd;
}

JNIEXPORT jint JNICALL
Java_lenovotouch_Bma253Native_nativeReadConfig(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    int fd = open_bma253();
    if (fd < 0) {
        return (jint)fd;
    }
    int range = i2c_read_reg(fd, BMA253_REG_PMU_RANGE);
    if (range < 0) {
        close(fd);
        return (jint)range;
    }
    int int9 = i2c_read_reg(fd, BMA253_REG_INT_9);
    close(fd);
    if (int9 < 0) {
        return (jint)int9;
    }
    return (jint)(((range & 0xFF) << 8) | (int9 & 0xFF));
}

JNIEXPORT jint JNICALL
Java_lenovotouch_Bma253Native_nativeWriteConfig(JNIEnv *env, jclass clazz, jint range, jint int9) {
    (void)env;
    (void)clazz;
    int fd = open_bma253();
    if (fd < 0) {
        return (jint)fd;
    }
    int rc = i2c_write_reg(fd, BMA253_REG_PMU_RANGE, (uint8_t)(range & 0xFF));
    if (rc < 0) {
        close(fd);
        return (jint)rc;
    }
    rc = i2c_write_reg(fd, BMA253_REG_INT_9, (uint8_t)(int9 & 0xFF));
    if (rc < 0) {
        close(fd);
        return (jint)rc;
    }
    int verifyRange = i2c_read_reg(fd, BMA253_REG_PMU_RANGE);
    int verifyInt9 = i2c_read_reg(fd, BMA253_REG_INT_9);
    close(fd);
    if (verifyRange < 0) return (jint)verifyRange;
    if (verifyInt9 < 0) return (jint)verifyInt9;
    if ((verifyRange & 0x0F) != (range & 0x0F) || (verifyInt9 & 0xDF) != (int9 & 0xDF)) {
        return (jint)-EIO;
    }
    return 0;
}
