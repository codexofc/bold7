/*
 * bold7-trackpad: the Q25's trackpad with the sensitivity of BlackBerry OS 7.1, in every app, and
 * never a mouse pointer; the Menu key as the 9900's; the thumb on the trackpad no longer taps the
 * bottom of the screen.
 *
 *   bold7-trackpad <moddir> [device mode [touch]]
 *                                   trackpad-start.sh, once boot completed and the SIM unlocked;
 *                                   device, mode and touch default to Q25_keyboard,
 *                                   /proc/q20_switch_key_mouse and s2716b_ts. test-trackpad.sh
 *                                   gives its own: with device and mode only, no touchscreen
 *   bold7-trackpad --fake <name>    test-trackpad.sh only: a stand-in for the keyboard (uinput, the
 *                                   keys and axes of Q25_keyboard), until killed
 *   bold7-trackpad --fake-touch <name>
 *                                   test-trackpad.sh only: a stand-in for the touchscreen (uinput,
 *                                   the axes and property of s2716b_ts), until killed
 *
 * The keyboard and the trackpad are one input device, Q25_keyboard. The driver (bbqX0kbd) turns
 * the trackpad into arrows itself (27 counts, the rest lost, no pause: docs/DEVICE.md) unless
 * /proc/q20_switch_key_mouse is 0, where it sends the raw counts as a mouse. This program:
 *   1. makes a twin of the keyboard with uinput (same name and ids: Android gives it the same
 *      Q25_keyboard.kl, .kcm and .idc), keys only, no pointer;
 *   2. grabs the real device (EVIOCGRAB: Android no longer sees its events), then puts the driver
 *      in raw mode;
 *   3. sends every key to the twin as it comes, the trackpad's click as Enter (what the driver
 *      sends in arrow mode), and the trackpad's counts as arrows cut by model.c from the
 *      settings bold7_trackpad_horizontal and bold7_trackpad_vertical;
 *   4. makes the Menu key (KEY_APPSELECT, Android's APP_SWITCH, which opens no menu) the 9900's:
 *      briefly, KEY_MENU at the release; held 400 ms, KEY_APPSELECT at once.
 *   5. takes the touchscreen the same way (a twin with its name, ids, location, property, axes
 *      and ranges, so Android gives it the same configuration; then the grab) and passes every
 *      contact on, but one that starts in the band at the bottom of the screen less than a delay
 *      after the trackpad's last activity, dropped for its whole life (bold7_trackpad_palm_band
 *      in px, bold7_trackpad_palm_delay in ms; 0 for either: none dropped, and the touchscreen is
 *      not taken at all at the next start). A touchscreen that cannot be taken is left to Android,
 *      the keyboard's part going on.
 * 3 to 5 are model.c's translation (tp_translate, tp_touch_translate), tested on the Mac; this
 * file only reads, writes and keeps the way back.
 *
 * Two processes, for the way back to Android to hold whatever happens to the one that works:
 *   - the parent opens the device, keeps it open, reads the settings (at start, then at each write
 *     of settings_global.xml, inotify: no polling) and never touches an event;
 *   - the worker, its child, does 1 to 4 on the same open file. A grab belongs to the open file:
 *     it holds while either process has it open.
 * When the worker ends, however (crash, kill -9, error), the parent writes 1 back to the driver,
 * and only then lets the file go: the keyboard and the arrows are Android's again, and no raw
 * count ever reaches Android, so no pointer; then it lets the touchscreen go. When the parent dies,
 * the worker sees its pipe close and does the same itself. Both killed at once (kill -9 of the
 * two): the kernel drops both grabs, the keyboard and the touchscreen work (the touchscreen has
 * no mode to put back), but the driver stays in raw mode and Android shows a MOUSE POINTER on the
 * trackpad's moves until tools/trackpad.sh retour, Q25TrackpadCustomizer's service turned back on
 * (it writes the mode at the next app change) or the next boot (the driver starts in arrow mode,
 * bbqX0kbd_probe).
 *
 * bold7_trackpad_off = 1 (`adb shell settings put global bold7_trackpad_off 1`): stops at once
 * and gives everything back; refuses to start while it is set. One instance at a time
 * (<moddir>/trackpad.lock). Nothing is restarted after an end: the next boot, or
 * `su -c 'setsid /data/adb/modules/bold7/bin/bold7-trackpad /data/adb/modules/bold7 </dev/null >/dev/null 2>&1 &'`.
 *
 * Log: bold7.log of the moddir, emptied at each boot (post-fs-data.sh).
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/signalfd.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "model.h"

#define SETTINGS_DIR "/data/system/users/0"
#define SETTINGS_FILE "settings_global.xml"
#define SETTINGS_BIN "/system/bin/settings"
/* All three settings read within this, system_server stuck or not. */
#define SETTINGS_TIMEOUT_MS 2000

/* Eyeballed: time for Android to open the twin before the real device is taken from it. */
#define TWIN_SETTLE_MS 1000
/* A key held at the grab would stay down for Android: wait for none, 50 ms at a time, 10 s at most. */
#define GRAB_TRIES 200
#define GRAB_RETRY_MS 50
/* Android's input threads: THREAD_PRIORITY_URGENT_DISPLAY. */
#define WORKER_NICE (-8)

/* Worker exit codes, told in the log by the parent. */
enum { END_PARENT_GONE = 0, END_NO_UINPUT = 3, END_BUSY = 4, END_KEYS_HELD = 5, END_MODE = 6, END_READ = 7, END_WRITE = 8 };

static const char *device_name = "Q25_keyboard";
static const char *mode_path = "/proc/q20_switch_key_mouse";
/* NULL: the touchscreen is not taken (test-trackpad.sh without a stand-in). */
static const char *touch_name = "s2716b_ts";
static FILE *logf;
/* Blocked in the parent, read by its signalfd; unblocked in its children. */
static sigset_t sigs;

static void say(const char *fmt, ...) {
    if (!logf) return;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    fprintf(logf, "trackpad[%d] %ld.%03ld: ", getpid(), (long) ts.tv_sec, ts.tv_nsec / 1000000);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(logf, fmt, ap);
    va_end(ap);
    fputc('\n', logf);
    fflush(logf);
}

static void sleep_ms(int ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000L};
    while (nanosleep(&ts, &ts) < 0 && errno == EINTR) {}
}

static int64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

static int64_t now_ms(void) {
    return now_us() / 1000;
}

static int test_bit(const unsigned long *bits, int bit) {
    return (bits[bit / (8 * sizeof(long))] >> (bit % (8 * sizeof(long)))) & 1;
}

/* The driver's mode: '0' raw, '1' arrows, 0 when unreadable. */
static char mode_read(void) {
    char c = 0;
    int fd = open(mode_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    if (read(fd, &c, 1) != 1) c = 0;
    close(fd);
    return c;
}

static int mode_write(char c) {
    int fd = open(mode_path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    int ok = write(fd, &c, 1) == 1;
    close(fd);
    return ok && mode_read() == c ? 0 : -1;
}

/* Back to Android: arrow mode first, then the device let go (a raw count never reaches Android). */
static void give_back(int dev) {
    if (mode_write('1') < 0) say("could not write 1 to %s (%s): run tools/trackpad.sh retour", mode_path, strerror(errno));
    ioctl(dev, EVIOCGRAB, 0);
    close(dev);
}

/* The touchscreen back to Android: nothing to put back but the grab. */
static void let_go(int fd) {
    if (fd < 0) return;
    ioctl(fd, EVIOCGRAB, 0);
    close(fd);
}

/*
 * One global setting through `settings get` (a fork, a few tens of ms), into [buf], by [deadline]
 * (monotonic ms); empty if unreadable. The parent only: the input path never waits on
 * system_server.
 */
static void setting(const char *name, char *buf, size_t size, int64_t deadline) {
    buf[0] = 0;
    int p[2];
    if (pipe2(p, O_CLOEXEC) < 0) return;
    pid_t pid = fork();
    if (pid == 0) {
        sigprocmask(SIG_UNBLOCK, &sigs, NULL);
        dup2(p[1], 1);
        execl(SETTINGS_BIN, "settings", "get", "global", name, (char *) NULL);
        _exit(127);
    }
    close(p[1]);
    size_t n = 0;
    if (pid > 0) {
        struct pollfd pf = {p[0], POLLIN, 0};
        int64_t left;
        while (n + 1 < size && (left = deadline - now_ms()) > 0 && poll(&pf, 1, (int) left) > 0) {
            ssize_t r = read(p[0], buf + n, size - 1 - n);
            if (r <= 0) break;
            n += r;
        }
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
    }
    buf[n] = 0;
    close(p[0]);
}

struct config {
    int off, horizontal, vertical, band, delay;
};

static int palm_on(struct config c) {
    return c.band > 0 && c.delay > 0;
}

static struct config read_config(void) {
    char v[64];
    struct config c;
    int64_t deadline = now_ms() + SETTINGS_TIMEOUT_MS;
    setting("bold7_trackpad_off", v, sizeof v, deadline);
    c.off = strcmp(v, "1\n") == 0 || strcmp(v, "1") == 0;
    setting("bold7_trackpad_horizontal", v, sizeof v, deadline);
    c.horizontal = tp_parse_sensitivity(v);
    setting("bold7_trackpad_vertical", v, sizeof v, deadline);
    c.vertical = tp_parse_sensitivity(v);
    setting("bold7_trackpad_palm_band", v, sizeof v, deadline);
    c.band = tp_parse_int(v, TP_PALM_BAND_DEFAULT, 0, TP_PALM_BAND_MAX);
    setting("bold7_trackpad_palm_delay", v, sizeof v, deadline);
    c.delay = tp_parse_int(v, TP_PALM_DELAY_DEFAULT, 0, TP_PALM_DELAY_MAX);
    return c;
}

#define BITS(max) ((max) / (8 * sizeof(long)) + 1)

/* The keyboard: the keys and the relative axes the driver has today. */
static int is_keyboard(int fd) {
    unsigned long rel[1] = {0}, key[BITS(KEY_MAX)] = {0};
    return ioctl(fd, EVIOCGBIT(EV_REL, sizeof rel), rel) >= 0 && test_bit(rel, REL_X) && test_bit(rel, REL_Y) &&
           ioctl(fd, EVIOCGBIT(EV_KEY, sizeof key), key) >= 0 && test_bit(key, BTN_MOUSE) && test_bit(key, KEY_ENTER);
}

/* The touchscreen: a direct multi-touch device of type B (slots, tracking ids). */
static int is_touchscreen(int fd) {
    unsigned long abs[BITS(ABS_MAX)] = {0}, prop[BITS(INPUT_PROP_MAX)] = {0};
    return ioctl(fd, EVIOCGBIT(EV_ABS, sizeof abs), abs) >= 0 && test_bit(abs, ABS_MT_SLOT) && test_bit(abs, ABS_MT_TRACKING_ID) &&
           test_bit(abs, ABS_MT_POSITION_X) && test_bit(abs, ABS_MT_POSITION_Y) && ioctl(fd, EVIOCGPROP(sizeof prop), prop) >= 0 &&
           test_bit(prop, INPUT_PROP_DIRECT);
}

/* The event device named [want] that [fits], its event times on the monotonic clock (as the
 * model's pause, the drop of pre-grab events and the touches' delay); -1 if none. */
static int open_device(const char *want, int (*fits)(int fd)) {
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while (found < 0 && (e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64], name[UINPUT_MAX_NAME_SIZE] = "";
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        if (ioctl(fd, EVIOCGNAME(sizeof name - 1), name) >= 0 && strcmp(name, want) == 0 && fits(fd)) {
            found = fd;
            say("%s is %s", want, path);
        } else {
            close(fd);
        }
    }
    closedir(d);
    int clk = CLOCK_MONOTONIC;
    if (found >= 0 && ioctl(found, EVIOCSCLOCKID, &clk) < 0) {
        say("monotonic event times refused for %s (%s)", want, strerror(errno));
        close(found);
        return -1;
    }
    return found;
}

/* ---- worker ---- */

static int twin = -1;
static int real_dev = -1;

/* The twin: the real device's name, ids and keys, the mouse buttons left out (no pointer class). */
static int make_twin(int dev) {
    unsigned long key[KEY_MAX / (8 * sizeof(long)) + 1] = {0};
    struct uinput_setup setup;
    memset(&setup, 0, sizeof setup);
    if (ioctl(dev, EVIOCGBIT(EV_KEY, sizeof key), key) < 0 || ioctl(dev, EVIOCGID, &setup.id) < 0 ||
        ioctl(dev, EVIOCGNAME(UINPUT_MAX_NAME_SIZE - 1), setup.name) < 0) return -1;
    twin = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (twin < 0) return -1;
    if (ioctl(twin, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(twin, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(twin, UI_SET_EVBIT, EV_MSC) < 0 || ioctl(twin, UI_SET_MSCBIT, MSC_SCAN) < 0) return -1;
    for (int k = 1; k < KEY_MAX; k++) {
        if (k >= BTN_MISC && k < KEY_OK) continue; /* BTN_MOUSE, BTN_RIGHT and their kin */
        if (test_bit(key, k) && ioctl(twin, UI_SET_KEYBIT, k) < 0) return -1;
    }
    /* The Menu key's short press (KEY_MENU, MENU in Q25_keyboard.kl), which the device itself never sends. */
    if (ioctl(twin, UI_SET_KEYBIT, KEY_MENU) < 0) return -1;
    /* The click (KEY_SELECT, DPAD_CENTER in Q25_keyboard.kl), which the device itself never sends either. */
    if (ioctl(twin, UI_SET_KEYBIT, KEY_SELECT) < 0) return -1;
    if (ioctl(twin, UI_DEV_SETUP, &setup) < 0 || ioctl(twin, UI_DEV_CREATE) < 0) return -1;
    return 0;
}

/*
 * The touchscreen's twin: its name, ids, location (Android merges it with the real one into one
 * input device, as it does the keyboard's), property (INPUT_PROP_DIRECT: a touchscreen), keys
 * and axes with their ranges, so Android gives it the same configuration and display.
 */
static int make_touch_twin(int fd) {
    unsigned long key[BITS(KEY_MAX)] = {0}, abs[BITS(ABS_MAX)] = {0}, prop[BITS(INPUT_PROP_MAX)] = {0};
    char phys[64] = "";
    struct uinput_setup setup;
    memset(&setup, 0, sizeof setup);
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof key), key) < 0 || ioctl(fd, EVIOCGBIT(EV_ABS, sizeof abs), abs) < 0 ||
        ioctl(fd, EVIOCGPROP(sizeof prop), prop) < 0 || ioctl(fd, EVIOCGID, &setup.id) < 0 ||
        ioctl(fd, EVIOCGNAME(UINPUT_MAX_NAME_SIZE - 1), setup.name) < 0) return -1;
    if (ioctl(fd, EVIOCGPHYS(sizeof phys - 1), phys) < 0) phys[0] = 0;
    int t = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (t < 0) return -1;
    int ok = ioctl(t, UI_SET_EVBIT, EV_SYN) >= 0 && ioctl(t, UI_SET_EVBIT, EV_KEY) >= 0 && ioctl(t, UI_SET_EVBIT, EV_ABS) >= 0;
    for (int k = 0; ok && k < KEY_MAX; k++)
        if (test_bit(key, k)) ok = ioctl(t, UI_SET_KEYBIT, k) >= 0;
    for (int a = 0; ok && a < ABS_MAX; a++) {
        if (!test_bit(abs, a)) continue;
        struct uinput_abs_setup s = {.code = (__u16) a};
        ok = ioctl(fd, EVIOCGABS(a), &s.absinfo) >= 0 && ioctl(t, UI_SET_ABSBIT, a) >= 0 && ioctl(t, UI_ABS_SETUP, &s) >= 0;
    }
    for (int p = 0; ok && p < INPUT_PROP_MAX; p++)
        if (test_bit(prop, p)) ok = ioctl(t, UI_SET_PROPBIT, p) >= 0;
    if (ok && phys[0]) ok = ioctl(t, UI_SET_PHYS, phys) >= 0;
    if (ok) ok = ioctl(t, UI_DEV_SETUP, &setup) >= 0 && ioctl(t, UI_DEV_CREATE) >= 0;
    if (ok) return t;
    close(t);
    return -1;
}

/* A twin's events, in one write. */
static int emit(int fd, const struct tp_out *out, int n) {
    if (n == 0) return 0;
    struct input_event evs[TP_TOUCH_OUT_MAX > 256 ? TP_TOUCH_OUT_MAX : 256];
    memset(evs, 0, sizeof evs[0] * n);
    for (int i = 0; i < n; i++) {
        evs[i].type = out[i].type;
        evs[i].code = out[i].code;
        evs[i].value = out[i].value;
    }
    ssize_t size = (ssize_t) (sizeof evs[0] * n);
    return write(fd, evs, size) == size ? 0 : -1;
}

static int64_t event_us(const struct input_event *ev) {
    return (int64_t) ev->input_event_sec * 1000000 + ev->input_event_usec;
}

/* A key down, or on the touchscreen a contact (BTN_TOUCH). */
static int any_key_down(int dev) {
    unsigned long key[KEY_MAX / (8 * sizeof(long)) + 1] = {0};
    if (ioctl(dev, EVIOCGKEY(sizeof key), key) < 0) return 1;
    for (size_t i = 0; i < sizeof key / sizeof key[0]; i++)
        if (key[i]) return 1;
    return 0;
}

/*
 * Grabs [dev] with no key or contact down, looked at before and after (one held at the grab would
 * stay down for Android); what was read before is Android's, dropped by its time. 0, END_KEYS_HELD
 * or END_BUSY.
 */
static int grab(int dev, const char *name, int64_t *grabbed_us) {
    struct input_event evs[64];
    for (int tries = 0;; tries++) {
        if (tries == GRAB_TRIES) {
            say("a key or a contact stays down on %s, nothing taken", name);
            return END_KEYS_HELD;
        }
        if (any_key_down(dev)) {
            sleep_ms(GRAB_RETRY_MS);
            continue;
        }
        while (read(dev, evs, sizeof evs) > 0) {}
        *grabbed_us = now_us();
        if (ioctl(dev, EVIOCGRAB, 1) < 0) {
            say("grab refused (%s): another program holds %s", strerror(errno), name);
            return END_BUSY;
        }
        if (!any_key_down(dev)) return 0;
        ioctl(dev, EVIOCGRAB, 0);
        sleep_ms(GRAB_RETRY_MS);
    }
}

/* The touchscreen, for tp_touch_hooks. */
static int touch_dev = -1;
static unsigned touch_have;

/* tp_touch_hooks: the real touchscreen's slots now. */
static int touch_slots(void *ctx, int val[TP_MT_COUNT][TP_SLOTS], int *slot) {
    (void) ctx;
    struct {
        __u32 code;
        __s32 values[TP_SLOTS];
    } req;
    for (int c = 0; c < TP_MT_COUNT; c++) {
        memset(&req, 0, sizeof req);
        req.code = TP_MT_FIRST + c;
        if ((touch_have & (1u << c)) && req.code != ABS_MT_SLOT && ioctl(touch_dev, EVIOCGMTSLOTS(sizeof req), &req) < 0) return -1;
        memcpy(val[c], req.values, sizeof req.values);
    }
    struct input_absinfo a;
    if (ioctl(touch_dev, EVIOCGABS(ABS_MT_SLOT), &a) < 0) return -1;
    *slot = a.value;
    return 0;
}

/* Takes the touchscreen (its twin already made): 0, or -1 with it left to Android. */
static int take_touch(int touch, struct tp_touch *tt, struct config c) {
    unsigned long abs[BITS(ABS_MAX)] = {0};
    struct input_absinfo slots, y;
    int64_t grabbed_us;
    if (ioctl(touch, EVIOCGBIT(EV_ABS, sizeof abs), abs) < 0 || ioctl(touch, EVIOCGABS(ABS_MT_SLOT), &slots) < 0 ||
        ioctl(touch, EVIOCGABS(ABS_MT_POSITION_Y), &y) < 0) {
        say("%s unreadable (%s): left to Android", touch_name, strerror(errno));
        return -1;
    }
    touch_dev = touch;
    touch_have = 0;
    for (int c = 0; c < TP_MT_COUNT; c++)
        if (test_bit(abs, TP_MT_FIRST + c)) touch_have |= 1u << c;
    if (grab(touch, touch_name, &grabbed_us) != 0) return -1;
    if (tp_touch_init(tt, (struct tp_touch_hooks) {NULL, touch_slots}, grabbed_us, y.minimum, y.maximum, slots.maximum + 1,
                      touch_have, c.band, c.delay) < 0) {
        say("%s: %d slots, y %d to %d, slots unreadable or more than %d: left to Android", touch_name, slots.maximum + 1,
            y.minimum, y.maximum, TP_SLOTS);
        ioctl(touch, EVIOCGRAB, 0);
        return -1;
    }
    say("touchscreen taken, touches ignored %d px from the bottom for %d ms after the trackpad", c.band, c.delay);
    return 0;
}

/* tp_hooks: arrows from the driver, raw mode again if it is in arrow mode. */
static int rearm(void *ctx) {
    (void) ctx;
    if (mode_read() != '1' || mode_write('0') != 0) return 0;
    static int count;
    say("driver back in arrow mode, raw mode set again (%d)", ++count);
    return 1;
}

/* tp_hooks: the real device's keys down. */
static int real_keys(void *ctx, uint64_t *bits) {
    (void) ctx;
    unsigned long key[KEY_MAX / (8 * sizeof(long)) + 1] = {0};
    if (ioctl(real_dev, EVIOCGKEY(sizeof key), key) < 0) return -1;
    memset(bits, 0, sizeof(uint64_t) * TP_KEY_WORDS);
    for (int k = 0; k <= KEY_MAX; k++)
        if (test_bit(key, k)) bits[k / 64] |= 1ull << (k % 64);
    return 0;
}

static int by_value(const void *a, const void *b) {
    return *(const int *) a - *(const int *) b;
}

/*
 * The delay this program adds, from an event's kernel time on the real device to its write on
 * the twin: logged every 100 key presses or 1000 touch reports (about 10 s of touching) as median,
 * 95th percentile and maximum, never which key nor where.
 */
struct latency {
    const char *what;
    int count, n;
    int us[1000];
};

static void latency(struct latency *l, int64_t event_time_us) {
    l->us[l->n++] = (int) (now_us() - event_time_us);
    if (l->n < l->count) return;
    qsort(l->us, l->n, sizeof l->us[0], by_value);
    say("%d %s, real to twin: median %d us, p95 %d us, max %d us", l->n, l->what, l->us[l->n / 2], l->us[l->n * 95 / 100],
        l->us[l->n - 1]);
    l->n = 0;
}

/* [touch]: the touchscreen, -1 when it is not taken. */
static int worker(int dev, int touch, int cfg, struct config c) {
    real_dev = dev;
    if (setpriority(PRIO_PROCESS, 0, WORKER_NICE) < 0) say("priority %d not set (%s)", WORKER_NICE, strerror(errno));
    if (make_twin(dev) < 0) {
        say("no uinput twin (%s)", strerror(errno));
        return END_NO_UINPUT;
    }
    int touch_twin = -1;
    if (touch >= 0 && (touch_twin = make_touch_twin(touch)) < 0) {
        say("no uinput twin for %s (%s): left to Android", touch_name, strerror(errno));
        touch = -1;
    }
    sleep_ms(TWIN_SETTLE_MS);

    /* The touchscreen first: waiting for a finger to lift (one is likely on it just after the
     * SIM's PIN) holds nothing back, the keyboard not being taken yet. */
    struct tp_touch tt;
    if (touch >= 0 && take_touch(touch, &tt, c) < 0) {
        close(touch_twin);
        touch = -1;
    }
    int64_t grabbed_us = 0;
    int end = grab(dev, device_name, &grabbed_us);
    if (end != 0) return end;
    if (mode_write('0') < 0) {
        say("could not write 0 to %s (%s)", mode_path, strerror(errno));
        return END_MODE;
    }
    say("trackpad taken, sensitivity %d horizontal, %d vertical", c.horizontal, c.vertical);

    struct tp_translator tr;
    tp_translator_init(&tr, (struct tp_hooks) {NULL, rearm, real_keys}, grabbed_us, c.horizontal, c.vertical);
    struct tp_out out[TP_TOUCH_OUT_MAX > 256 ? TP_TOUCH_OUT_MAX : 256];
    const int max = sizeof out / sizeof out[0];
    struct input_event evs[64];
    static struct latency keys = {"key presses", 100, 0, {0}}, touches = {"touch reports", 1000, 0, {0}};
    /* The keyboard before the touchscreen: a trackpad event read with a touch counts for it. */
    struct pollfd pf[3] = {{cfg, POLLIN, 0}, {dev, POLLIN, 0}, {touch, POLLIN, 0}};
    for (;;) {
        /* The Menu key held past its threshold: the switcher now, while it is still down. */
        int64_t now = now_ms();
        if (emit(twin, out, tp_translate_tick(&tr, now, out, max)) < 0) {
            say("write to the twin failed (%s)", strerror(errno));
            return END_WRITE;
        }
        int64_t deadline = tp_menu_deadline(&tr.menu);
        int timeout = deadline < 0 ? -1 : (int) (deadline > now ? deadline - now : 0);
        int ready = poll(pf, 3, timeout);
        if (ready < 0) {
            if (errno == EINTR) continue;
            return END_READ;
        }
        if (ready == 0) continue;
        if (pf[0].revents) {
            struct config n;
            if (read(cfg, &n, sizeof n) != (ssize_t) sizeof n) {
                say("parent gone, trackpad and touchscreen given back");
                give_back(dev);
                let_go(touch);
                return END_PARENT_GONE;
            }
            tp_set_sensitivity(&tr.model, n.horizontal, n.vertical);
            say("sensitivity %d horizontal, %d vertical", n.horizontal, n.vertical);
            if (touch >= 0) {
                tp_touch_set(&tt, n.band, n.delay);
                say("touches ignored %d px from the bottom for %d ms after the trackpad", n.band, n.delay);
            } else if (palm_on(n)) {
                say("touchscreen not taken: touches at the bottom ignored from the next start");
            }
        }
        if (pf[1].revents) {
            ssize_t r = read(dev, evs, sizeof evs);
            if (r <= 0 && !(r < 0 && errno == EAGAIN)) {
                say("read of %s failed (%s)", device_name, r < 0 ? strerror(errno) : "end");
                return END_READ;
            }
            for (ssize_t i = 0; i < r / (ssize_t) sizeof evs[0]; i++) {
                const struct input_event *ev = &evs[i];
                int n = tp_translate(&tr, event_us(ev), ev->type, ev->code, ev->value, out, max);
                if (emit(twin, out, n) < 0) {
                    say("write to the twin failed (%s)", strerror(errno));
                    return END_WRITE;
                }
                if (n && ev->type == EV_KEY && ev->value == 1) latency(&keys, event_us(ev));
            }
        }
        if (touch >= 0 && pf[2].revents) {
            ssize_t r = read(touch, evs, sizeof evs);
            if (r <= 0 && !(r < 0 && errno == EAGAIN)) {
                say("read of %s failed (%s)", touch_name, r < 0 ? strerror(errno) : "end");
                return END_READ;
            }
            for (ssize_t i = 0; i < r / (ssize_t) sizeof evs[0]; i++) {
                const struct input_event *ev = &evs[i];
                unsigned rejected = tt.rejected;
                int n = tp_touch_translate(&tt, tr.trackpad_us, event_us(ev), ev->type, ev->code, ev->value, out, max);
                if (emit(touch_twin, out, n) < 0) {
                    say("write to the touchscreen's twin failed (%s)", strerror(errno));
                    return END_WRITE;
                }
                if (tt.rejected != rejected)
                    say("touch ignored at %d px, %d ms after the trackpad (%u so far)", tt.last_px, tt.last_after_ms, tt.rejected);
                if (n) latency(&touches, event_us(ev));
            }
        }
    }
}

/* ---- the stand-in keyboard of test-trackpad.sh ---- */

static int fake(const char *name) {
    static const int keys[] = {KEY_A, KEY_ENTER, KEY_LEFTSHIFT, KEY_BACK, KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,
                               KEY_APPSELECT, BTN_MOUSE, BTN_RIGHT};
    struct uinput_setup setup;
    memset(&setup, 0, sizeof setup);
    snprintf(setup.name, sizeof setup.name, "%s", name);
    setup.id.bustype = BUS_VIRTUAL;
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (fd < 0 || ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_REL) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_MSC) < 0 || ioctl(fd, UI_SET_MSCBIT, MSC_SCAN) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_X) < 0 || ioctl(fd, UI_SET_RELBIT, REL_Y) < 0) return 1;
    for (size_t i = 0; i < sizeof keys / sizeof keys[0]; i++)
        if (ioctl(fd, UI_SET_KEYBIT, keys[i]) < 0) return 1;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) return 1;
    for (;;) pause();
}

/* The stand-in touchscreen: the keys, axes, ranges and property of s2716b_ts (getevent -lp). */
static int fake_touch(const char *name) {
    static const int axes[][2] = {{ABS_MT_SLOT, 9}, {ABS_MT_TOUCH_MAJOR, 126}, {ABS_MT_TOUCH_MINOR, 126},
                                  {ABS_MT_POSITION_X, 2492}, {ABS_MT_POSITION_Y, 2492}, {ABS_MT_TRACKING_ID, 65535}};
    struct uinput_setup setup;
    memset(&setup, 0, sizeof setup);
    snprintf(setup.name, sizeof setup.name, "%s", name);
    setup.id.bustype = BUS_VIRTUAL;
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (fd < 0 || ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0 || ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER) < 0 ||
        ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0 || ioctl(fd, UI_SET_PHYS, "bold7-test/touch") < 0) return 1;
    for (size_t i = 0; i < sizeof axes / sizeof axes[0]; i++) {
        struct uinput_abs_setup s = {.code = (__u16) axes[i][0], .absinfo = {.maximum = axes[i][1]}};
        if (ioctl(fd, UI_ABS_SETUP, &s) < 0) return 1;
    }
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) return 1;
    for (;;) pause();
}

/* ---- parent ---- */

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--fake") == 0) return fake(argv[2]);
    if (argc == 3 && strcmp(argv[1], "--fake-touch") == 0) return fake_touch(argv[2]);
    if (argc != 2 && argc != 4 && argc != 5) {
        fprintf(stderr, "usage: bold7-trackpad <moddir> [device mode [touch]] | --fake <name> | --fake-touch <name>\n");
        return 2;
    }
    if (argc >= 4) {
        device_name = argv[2];
        mode_path = argv[3];
        /* Never the real touchscreen when the keyboard is a stand-in. */
        touch_name = argc == 5 ? argv[4] : NULL;
    }
    char path[512];
    snprintf(path, sizeof path, "%s/bold7.log", argv[1]);
    logf = fopen(path, "ae");

    /* One at a time: the lock lives as long as the parent and the worker, which share it. */
    snprintf(path, sizeof path, "%s/trackpad.lock", argv[1]);
    int lock = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) {
        say("another bold7-trackpad holds %s: not started", path);
        return 1;
    }

    struct config c = read_config();
    if (c.off) {
        say("bold7_trackpad_off is 1: not started");
        return 0;
    }
    char m = mode_read();
    if (m != '0' && m != '1') {
        say("%s unreadable: not started (driver changed?)", mode_path);
        return 1;
    }
    int dev = open_device(device_name, is_keyboard);
    if (dev < 0) {
        say("%s with its trackpad not found: not started", device_name);
        return 1;
    }
    /* The touchscreen only when touches are to be ignored; without it, the keyboard's part alone. */
    int touch = -1;
    if (touch_name && !palm_on(c)) {
        say("bold7_trackpad_palm_band or _delay is 0: %s left to Android", touch_name);
    } else if (touch_name && (touch = open_device(touch_name, is_touchscreen)) < 0) {
        say("%s as a multi-touch screen not found: left to Android", touch_name);
    }

    int watch = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (watch < 0 || inotify_add_watch(watch, SETTINGS_DIR, IN_CLOSE_WRITE | IN_MOVED_TO) < 0) {
        say("cannot watch " SETTINGS_DIR " (%s): not started", strerror(errno));
        close(dev);
        return 1;
    }
    sigemptyset(&sigs);
    sigaddset(&sigs, SIGCHLD);
    sigaddset(&sigs, SIGTERM);
    sigaddset(&sigs, SIGINT);
    sigaddset(&sigs, SIGHUP);
    sigprocmask(SIG_BLOCK, &sigs, NULL);
    int sigfd = signalfd(-1, &sigs, SFD_CLOEXEC);
    int cfg[2];
    if (sigfd < 0 || pipe2(cfg, O_CLOEXEC) < 0) {
        say("signalfd or pipe failed (%s): not started", strerror(errno));
        close(dev);
        return 1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        say("fork failed (%s): not started", strerror(errno));
        close(dev);
        return 1;
    }
    if (pid == 0) {
        sigprocmask(SIG_UNBLOCK, &sigs, NULL);
        close(watch);
        close(sigfd);
        close(cfg[1]);
        _exit(worker(dev, touch, cfg[0], c));
    }
    close(cfg[0]);
    signal(SIGPIPE, SIG_IGN);
    say("worker %d started", pid);

    struct pollfd pf[2] = {{sigfd, POLLIN, 0}, {watch, POLLIN, 0}};
    int status = 0, reaped = 0;
    for (;;) {
        if (poll(pf, 2, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pf[0].revents) {
            struct signalfd_siginfo si;
            if (read(sigfd, &si, sizeof si) == (ssize_t) sizeof si && si.ssi_signo != SIGCHLD) {
                say("signal %d: stopping", si.ssi_signo);
                kill(pid, SIGKILL);
            }
            /* A SIGCHLD may also be a `settings` child, already reaped by setting(). */
            if (waitpid(pid, &status, WNOHANG) == pid) {
                reaped = 1;
                break;
            }
        }
        if (pf[1].revents) {
            char buf[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
            int ours = 0;
            ssize_t r;
            while ((r = read(watch, buf, sizeof buf)) > 0)
                for (char *p = buf; p < buf + r;) {
                    struct inotify_event *e = (struct inotify_event *) p;
                    if (e->len && strcmp(e->name, SETTINGS_FILE) == 0) ours = 1;
                    p += sizeof *e + e->len;
                }
            if (!ours) continue;
            struct config n = read_config();
            if (n.off) {
                say("bold7_trackpad_off is 1: stopping");
                kill(pid, SIGKILL);
            } else if (n.horizontal != c.horizontal || n.vertical != c.vertical || n.band != c.band || n.delay != c.delay) {
                c = n;
                if (write(cfg[1], &c, sizeof c) < 0) say("settings not passed on (%s)", strerror(errno));
            }
        }
    }
    if (!reaped) {
        /* poll failed: the worker must not outlive the parent holding the device. */
        kill(pid, SIGKILL);
        while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    }
    give_back(dev);
    let_go(touch);
    if (WIFEXITED(status)) say("worker ended (%d), trackpad, keyboard and touchscreen given back to Android", WEXITSTATUS(status));
    else if (WIFSIGNALED(status))
        say("worker killed (signal %d), trackpad, keyboard and touchscreen given back to Android", WTERMSIG(status));
    return 0;
}
