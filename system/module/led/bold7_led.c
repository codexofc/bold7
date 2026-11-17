/*
 * bold7-led: the Q25's LED flashes as the 9900's, in the colour Bold7 chose.
 *
 *   bold7-led <moddir>                    boot-completed.sh, until reboot
 *   bold7-led <moddir> <leds> <setting>   test-led.sh only: a folder of stand-in LEDs
 *                                         (<leds>/{red,green,blue}/{brightness,trigger}) and a
 *                                         setting of its own, never the real ones
 *
 * The launcher decides (apps/launcher, led/): it writes the colour to the global setting
 * bold7_led ("red", "amber", "green", "blue", "off"). This program reads it at start, then at
 * each write of settings_global.xml (inotify: no polling), and flashes it (model.c).
 *
 * Why not the kernel's timer trigger: two timers, one per GPIO, drift apart (measured on the Q25,
 * red and green 60 to 140 ms apart after a minute, amber no longer one flash). One
 * process writes the three brightness files together instead, waking twice a period while a
 * colour flashes, never while none does. Nothing keeps the phone awake: when the kernel suspends,
 * leds-gpio turns the LED off itself (no retain-state-suspended in the Q25's device tree) and
 * lights it back at the wake-up, and the flash goes on from there.
 *
 * Android's lights HAL writes the same files: the framework's RRO turns Android's notification LED
 * off (config_intrusiveNotificationLed), so nothing but a lit battery light of the ROM would
 * write; a write of the HAL lasts until the next flash.
 *
 * The way back: at start, each LED's trigger and brightness are kept; at SIGTERM, SIGINT or SIGHUP
 * they are written back, the LED is Android's again. One instance at a time (<moddir>/led.lock).
 * Nothing is restarted after an end: the next boot, or
 * `su -c 'setsid /data/adb/modules/bold7/bin/bold7-led /data/adb/modules/bold7 </dev/null >/dev/null 2>&1 &'`.
 *
 * Log: bold7.log of the moddir, emptied at each boot (post-fs-data.sh).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/signalfd.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "model.h"

#define SETTINGS_DIR "/data/system/users/0"
#define SETTINGS_FILE "settings_global.xml"
#define SETTINGS_BIN "/system/bin/settings"
#define SETTINGS_TIMEOUT_MS 2000

static const char *names[] = {"red", "green", "blue"};
static const int bits[] = {LED_RED, LED_GREEN, LED_BLUE};
#define COUNT 3

static const char *leds = "/sys/class/leds";
static const char *setting_name = "bold7_led";
static FILE *logf;

/* Each LED as found at start: what goes back. */
static char kept_trigger[COUNT][64];
static char kept_brightness[COUNT][16];

static void say(const char *fmt, ...) {
    if (!logf) return;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    fprintf(logf, "led[%d] %ld.%03ld: ", getpid(), (long) ts.tv_sec, ts.tv_nsec / 1000000);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(logf, fmt, ap);
    va_end(ap);
    fputc('\n', logf);
    fflush(logf);
}

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int read_file(int i, const char *file, char *buf, size_t size) {
    char path[256];
    snprintf(path, sizeof path, "%s/%s/%s", leds, names[i], file);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, size - 1);
    close(fd);
    if (n < 0) return -1;
    buf[n] = 0;
    buf[strcspn(buf, "\n")] = 0;
    return 0;
}

static int write_file(int i, const char *file, const char *value) {
    char path[256];
    snprintf(path, sizeof path, "%s/%s/%s", leds, names[i], file);
    int fd = open(path, O_WRONLY | O_TRUNC | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = write(fd, value, strlen(value));
    close(fd);
    return n == (ssize_t) strlen(value) ? 0 : -1;
}

/* The selected trigger, the one in brackets of "none [timer] ...", or the whole file without any. */
static void selected(const char *list, char *out, size_t size) {
    const char *open = strchr(list, '['), *close = open ? strchr(open, ']') : NULL;
    if (open && close) snprintf(out, size, "%.*s", (int) (close - open - 1), open + 1);
    else snprintf(out, size, "%s", list);
}

/* The LEDs in [mask] lit or not; a failed write said once per change. */
static void show(int mask, int on) {
    for (int i = 0; i < COUNT; i++)
        if (write_file(i, "brightness", on && (mask & bits[i]) ? "255" : "0") < 0)
            say("cannot write %s/%s/brightness (%s)", leds, names[i], strerror(errno));
}

static void give_back(void) {
    /* Brightness first: a 0 written after the trigger would drop it again. */
    for (int i = 0; i < COUNT; i++) {
        write_file(i, "brightness", kept_brightness[i]);
        if (strcmp(kept_trigger[i], "none") != 0 && write_file(i, "trigger", kept_trigger[i]) < 0)
            say("could not give the trigger %s back to %s", kept_trigger[i], names[i]);
    }
}

/* The setting through `settings get` (a fork, a few tens of ms); empty if unreadable within the timeout. */
static void setting(char *buf, size_t size) {
    buf[0] = 0;
    int p[2];
    if (pipe2(p, O_CLOEXEC) < 0) return;
    pid_t pid = fork();
    if (pid == 0) {
        dup2(p[1], 1);
        execl(SETTINGS_BIN, "settings", "get", "global", setting_name, (char *) NULL);
        _exit(127);
    }
    close(p[1]);
    size_t n = 0;
    if (pid > 0) {
        int64_t deadline = now_ms() + SETTINGS_TIMEOUT_MS, left;
        struct pollfd pf = {p[0], POLLIN, 0};
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

int main(int argc, char **argv) {
    if (argc != 2 && argc != 4) {
        fprintf(stderr, "usage: bold7-led <moddir> [leds setting]\n");
        return 2;
    }
    if (argc == 4) {
        leds = argv[2];
        setting_name = argv[3];
    }
    char path[512];
    snprintf(path, sizeof path, "%s/bold7.log", argv[1]);
    logf = fopen(path, "ae");

    snprintf(path, sizeof path, "%s/led.lock", argv[1]);
    int lock = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) {
        say("another bold7-led holds %s: not started", path);
        return 1;
    }

    for (int i = 0; i < COUNT; i++) {
        char list[1024];
        if (read_file(i, "trigger", list, sizeof list) < 0 || read_file(i, "brightness", kept_brightness[i], sizeof kept_brightness[i]) < 0) {
            say("%s/%s unreadable: not started", leds, names[i]);
            return 1;
        }
        selected(list, kept_trigger[i], sizeof kept_trigger[i]);
    }

    int watch = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (watch < 0 || inotify_add_watch(watch, SETTINGS_DIR, IN_CLOSE_WRITE | IN_MOVED_TO) < 0) {
        say("cannot watch " SETTINGS_DIR " (%s): not started", strerror(errno));
        return 1;
    }
    sigset_t sigs;
    sigemptyset(&sigs);
    sigaddset(&sigs, SIGTERM);
    sigaddset(&sigs, SIGINT);
    sigaddset(&sigs, SIGHUP);
    sigprocmask(SIG_BLOCK, &sigs, NULL);
    int sigfd = signalfd(-1, &sigs, SFD_CLOEXEC);
    if (sigfd < 0) {
        say("signalfd failed (%s): not started", strerror(errno));
        return 1;
    }

    /* Ours from here: a trigger would blink on its own. */
    for (int i = 0; i < COUNT; i++)
        if (strcmp(kept_trigger[i], "none") != 0) write_file(i, "trigger", "none");
    say("started, %s: red %s %s, green %s %s, blue %s %s", leds, kept_trigger[0], kept_brightness[0],
        kept_trigger[1], kept_brightness[1], kept_trigger[2], kept_brightness[2]);

    char value[64];
    setting(value, sizeof value);
    int mask = led_parse(value), on = 0, wait = -1;
    int64_t start = now_ms();
    say("%s: %s", setting_name, mask ? value : "off");
    if (mask) on = led_lit(0, &wait);
    show(mask, on);

    struct pollfd pf[2] = {{sigfd, POLLIN, 0}, {watch, POLLIN, 0}};
    for (;;) {
        int r = poll(pf, 2, mask ? wait : -1);
        if (r < 0) {
            if (errno == EINTR) continue;
            say("poll failed (%s): stopping", strerror(errno));
            break;
        }
        if (pf[0].revents) {
            struct signalfd_siginfo si;
            if (read(sigfd, &si, sizeof si) == (ssize_t) sizeof si) say("signal %d: stopping", si.ssi_signo);
            break;
        }
        if (pf[1].revents) {
            char buf[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
            int ours = 0;
            ssize_t n;
            while ((n = read(watch, buf, sizeof buf)) > 0)
                for (char *p = buf; p < buf + n;) {
                    struct inotify_event *e = (struct inotify_event *) p;
                    if (e->len && strcmp(e->name, SETTINGS_FILE) == 0) ours = 1;
                    p += sizeof *e + e->len;
                }
            if (ours) {
                setting(value, sizeof value);
                int m = led_parse(value);
                if (m != mask) {
                    say("%s: %s", setting_name, m ? value : "off");
                    mask = m;
                    start = now_ms();
                    wait = -1;
                    on = mask ? led_lit(0, &wait) : 0;
                    show(mask, on);
                    continue;
                }
            }
        }
        if (mask) {
            int lit = led_lit(now_ms() - start, &wait);
            if (lit != on) show(mask, on = lit);
        }
    }
    give_back();
    say("LED given back to Android");
    return 0;
}
