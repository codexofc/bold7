/*
 * Tests of model.c, built and run on the Mac by ./gradlew test (:system:module:trackpadTest).
 * Exits non-zero at the first failure.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "model.h"

static int failures;

#define CHECK(cond)                                                    \
    do {                                                               \
        if (!(cond)) {                                                 \
            fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #cond); \
            failures++;                                                \
        }                                                              \
    } while (0)

/* The steps of one read, as a string of U D L R. */
static const char *feed(struct tp_model *m, int64_t ms, int dx, int dy) {
    static char s[64];
    enum tp_dir out[32];
    int n = tp_feed(m, ms, dx, dy, out, 32);
    for (int i = 0; i < n; i++) s[i] = "UDLR"[out[i]];
    s[n] = 0;
    return s;
}

/* The steps of reads every 8 ms of [dx] [dy] each, [count] of them, from [ms]. */
static const char *sweep(struct tp_model *m, int64_t ms, int count, int dx, int dy) {
    static char all[256];
    all[0] = 0;
    for (int i = 0; i < count; i++) strcat(all, feed(m, ms + 8 * i, dx, dy));
    return all;
}

static void parse(void) {
    CHECK(tp_parse_sensitivity(NULL) == 70);
    CHECK(tp_parse_sensitivity("null") == 70);
    CHECK(tp_parse_sensitivity("") == 70);
    CHECK(tp_parse_sensitivity("abc") == 70);
    CHECK(tp_parse_sensitivity("80x") == 70);
    CHECK(tp_parse_sensitivity("90") == 90);
    CHECK(tp_parse_sensitivity("90\n") == 90);
    CHECK(tp_parse_sensitivity("20") == 20);
    CHECK(tp_parse_sensitivity("5") == 20);
    CHECK(tp_parse_sensitivity("-40") == 20);
    CHECK(tp_parse_sensitivity("250") == 100);
    CHECK(tp_parse_sensitivity("99999999999999999999") == 100);
}

static void thresholds(void) {
    CHECK(tp_threshold(70) == TP_THRESHOLD_70);
    /* Higher sensitivity, lower threshold; inversely proportional, rounded. */
    CHECK(tp_threshold(100) == 42);
    CHECK(tp_threshold(80) == 53);
    CHECK(tp_threshold(20) == 210);
    for (int s = TP_SENS_MIN; s < TP_SENS_MAX; s += 10) CHECK(tp_threshold(s) > tp_threshold(s + 10));
    CHECK(tp_threshold(0) == tp_threshold(20));
    CHECK(tp_threshold(500) == tp_threshold(100));
}

static void steps_and_rest(void) {
    struct tp_model m;
    tp_init(&m, 70, 70);
    /* 59 counts: nothing; one more: a step to the right. */
    CHECK(strcmp(feed(&m, 0, 59, 0), "") == 0);
    CHECK(strcmp(feed(&m, 8, 1, 0), "R") == 0);
    /* The rest is kept: 50 then 10 more make the second step. */
    CHECK(strcmp(feed(&m, 16, 50, 0), "") == 0);
    CHECK(strcmp(feed(&m, 24, 10, 0), "R") == 0);
    /* Past the threshold, the rest counts toward the next step: 70 then 50. */
    CHECK(strcmp(feed(&m, 32, 70, 0), "R") == 0);
    CHECK(strcmp(feed(&m, 40, 50, 0), "R") == 0);
    /* Two thresholds in one read, two steps. */
    tp_init(&m, 100, 100);
    CHECK(strcmp(feed(&m, 0, -84, 0), "LL") == 0);
    /* Down and up. */
    tp_init(&m, 70, 70);
    CHECK(strcmp(feed(&m, 0, 0, 60), "D") == 0);
    CHECK(strcmp(feed(&m, 8, 0, -60), "U") == 0);
}

static void max_steps(void) {
    struct tp_model m;
    enum tp_dir out[2];
    tp_init(&m, 100, 100);
    CHECK(tp_feed(&m, 0, 42 * 5, 0, out, 2) == 2);
}

static void pause_forgets(void) {
    struct tp_model m;
    tp_init(&m, 70, 70);
    /* 40 counts, a pause longer than the window, 40 more: no step (a nudge after a rest). */
    CHECK(strcmp(feed(&m, 0, 40, 0), "") == 0);
    CHECK(strcmp(feed(&m, TP_WINDOW_MS + 1, 40, 0), "") == 0);
    /* Within the window, they add up. */
    CHECK(strcmp(feed(&m, TP_WINDOW_MS + 1 + TP_WINDOW_MS, 20, 0), "R") == 0);
    /* The rest past a step is forgotten after a pause too. */
    tp_init(&m, 70, 70);
    CHECK(strcmp(feed(&m, 0, 110, 0), "R") == 0);
    CHECK(strcmp(feed(&m, 1000, 20, 0), "") == 0);
}

static void turn_back(void) {
    struct tp_model m;
    tp_init(&m, 70, 70);
    /* 50 to the right, then the other way: the left starts from zero. */
    CHECK(strcmp(feed(&m, 0, 50, 0), "") == 0);
    CHECK(strcmp(feed(&m, 8, -50, 0), "") == 0);
    CHECK(strcmp(feed(&m, 16, -10, 0), "L") == 0);
}

static void one_axis(void) {
    struct tp_model m;
    tp_init(&m, 70, 70);
    /* A horizontal sweep drifting down, as the thumb's arc on the Q25 (Y about a third of X): only
     * right steps, the vertical forgotten at each. */
    CHECK(strcmp(sweep(&m, 0, 30, 6, 2), "RRR") == 0);
    /* Both past their thresholds: the larger one wins, the other is forgotten. */
    tp_init(&m, 70, 70);
    CHECK(strcmp(feed(&m, 0, 40, 50), "") == 0);
    CHECK(strcmp(feed(&m, 8, 25, 5), "R") == 0);
    CHECK(strcmp(feed(&m, 16, 0, 59), "") == 0);
}

static void per_axis_sensitivity(void) {
    struct tp_model m;
    /* Horizontal 100 (42 counts), vertical 20 (210). */
    tp_init(&m, 100, 20);
    CHECK(strcmp(feed(&m, 0, 42, 0), "R") == 0);
    CHECK(strcmp(sweep(&m, 8, 20, 0, 10), "") == 0);
    CHECK(strcmp(feed(&m, 8 + 8 * 20, 0, 10), "D") == 0);
    /* The ratio decides between axes: 60 of 210 down loses to 42 of 42 right. */
    tp_init(&m, 100, 20);
    CHECK(strcmp(feed(&m, 0, 42, 100), "R") == 0);
}

static void settings_change(void) {
    struct tp_model m;
    tp_init(&m, 70, 70);
    CHECK(strcmp(feed(&m, 0, 45, 0), "") == 0);
    /* Up to 100 (42 counts): the 45 already counted make a step at the next read. */
    tp_set_sensitivity(&m, 100, 100);
    CHECK(strcmp(feed(&m, 8, 1, 0), "R") == 0);
}

static void menu_key(void) {
    struct tp_menu k = {0};
    /* No key down: no deadline, a stray release does nothing. */
    CHECK(tp_menu_deadline(&k) == -1);
    CHECK(tp_menu_up(&k, 50) == TP_MENU_NONE);
    /* Short: Menu at the release. */
    tp_menu_down(&k, 1000);
    CHECK(tp_menu_deadline(&k) == 1000 + TP_MENU_LONG_MS);
    CHECK(tp_menu_tick(&k, 1000 + TP_MENU_LONG_MS - 1) == TP_MENU_NONE);
    CHECK(tp_menu_up(&k, 1120) == TP_MENU_SHORT);
    CHECK(tp_menu_deadline(&k) == -1);
    CHECK(tp_menu_tick(&k, 5000) == TP_MENU_NONE);
    /* Long: the switcher as soon as the threshold passes, once, nothing at the release. */
    tp_menu_down(&k, 2000);
    CHECK(tp_menu_tick(&k, 2000 + TP_MENU_LONG_MS) == TP_MENU_LONG);
    CHECK(tp_menu_deadline(&k) == -1);
    CHECK(tp_menu_tick(&k, 2000 + 2 * TP_MENU_LONG_MS) == TP_MENU_NONE);
    CHECK(tp_menu_up(&k, 3650) == TP_MENU_NONE);
    /* A release past the threshold whose tick never came (a late wake-up): long, not short. */
    tp_menu_down(&k, 4000);
    CHECK(tp_menu_up(&k, 4000 + TP_MENU_LONG_MS) == TP_MENU_LONG);
    /* A second down without a release (a lost event) starts again. */
    tp_menu_down(&k, 6000);
    tp_menu_down(&k, 6300);
    CHECK(tp_menu_tick(&k, 6000 + TP_MENU_LONG_MS) == TP_MENU_NONE);
    CHECK(tp_menu_tick(&k, 6300 + TP_MENU_LONG_MS) == TP_MENU_LONG);
}

/* ---- the translation of the event loop ---- */

struct fake {
    int rearm_result, rearms;
    uint64_t real[TP_KEY_WORDS];
};

static int fake_rearm(void *ctx) {
    struct fake *f = ctx;
    f->rearms++;
    return f->rearm_result;
}

static int fake_keys(void *ctx, uint64_t *bits) {
    struct fake *f = ctx;
    memcpy(bits, f->real, sizeof f->real);
    return 0;
}

static struct fake dev;
static struct tp_translator tr;

static void start(int64_t grabbed_us) {
    memset(&dev, 0, sizeof dev);
    struct tp_hooks hooks = {&dev, fake_rearm, fake_keys};
    tp_translator_init(&tr, hooks, grabbed_us, 70, 70);
}

/* The twin's events as text: "M<value>" a scan code, "K<code>:<value>" a key, "S" a report. */
static char said[4096];

static void show(const struct tp_out *out, int n) {
    for (int i = 0; i < n; i++) {
        char one[32];
        if (out[i].type == EV_MSC) snprintf(one, sizeof one, "M%d ", out[i].value);
        else if (out[i].type == EV_KEY) snprintf(one, sizeof one, "K%d:%d ", out[i].code, out[i].value);
        else if (out[i].type == EV_SYN && out[i].code == SYN_REPORT) snprintf(one, sizeof one, "S ");
        else snprintf(one, sizeof one, "?%d.%d ", out[i].type, out[i].code);
        strcat(said, one);
    }
}

/* Feeds events {us, type, code, value}, ended by a zero type and time; returns the twin's. */
static const char *in(const int64_t (*evs)[4]) {
    struct tp_out out[256];
    said[0] = 0;
    for (; evs[0][0] || evs[0][1]; evs++) show(out, tp_translate(&tr, evs[0][0], (int) evs[0][1], (int) evs[0][2], (int) evs[0][3], out, 256));
    size_t n = strlen(said);
    if (n) said[n - 1] = 0;
    return said;
}

static const char *tick(int64_t ms) {
    struct tp_out out[64];
    said[0] = 0;
    show(out, tp_translate_tick(&tr, ms, out, 64));
    size_t n = strlen(said);
    if (n) said[n - 1] = 0;
    return said;
}

#define END {0, 0, 0, 0}
#define SAYS(events, expected)                                                                \
    do {                                                                                      \
        static const int64_t evs_[][4] = events;                                              \
        const char *got_ = in(evs_);                                                          \
        if (strcmp(got_, expected) != 0) {                                                    \
            fprintf(stderr, "%s:%d: got \"%s\", expected \"%s\"\n", __FILE__, __LINE__, got_, \
                    expected);                                                                \
            failures++;                                                                       \
        }                                                                                     \
    } while (0)
#define EVS(...) {__VA_ARGS__, END}

static void keys_pass(void) {
    start(1000);
    /* A key with its scan code, in the driver's order, then its report. */
    SAYS(EVS({2000, EV_MSC, MSC_SCAN, 30}, {2000, EV_KEY, KEY_A, 1}, {2000, EV_SYN, SYN_REPORT, 0}), "M30 K30:1 S");
    SAYS(EVS({2100, EV_MSC, MSC_SCAN, 30}, {2100, EV_KEY, KEY_A, 0}, {2100, EV_SYN, SYN_REPORT, 0}), "M30 K30:0 S");
    /* Back and Shift as they come. */
    SAYS(EVS({3000, EV_KEY, KEY_BACK, 1}, {3000, EV_SYN, SYN_REPORT, 0}), "K158:1 S");
    SAYS(EVS({3000, EV_KEY, KEY_LEFTSHIFT, 1}, {3000, EV_SYN, SYN_REPORT, 0}), "K42:1 S");
}

static void before_grab_dropped(void) {
    start(5000);
    /* Read before the grab: Android had them already. */
    SAYS(EVS({4999, EV_KEY, KEY_A, 1}, {4999, EV_SYN, SYN_REPORT, 0}), "");
    SAYS(EVS({5000, EV_KEY, KEY_A, 0}, {5000, EV_SYN, SYN_REPORT, 0}), "K30:0 S");
}

static void click_is_select(void) {
    start(0);
    /* The 9900's click is not Enter (sim-97b against sim-95c): KEY_SELECT, DPAD_CENTER in Q25_keyboard.kl. */
    SAYS(EVS({1000, EV_KEY, BTN_MOUSE, 1}, {1000, EV_SYN, SYN_REPORT, 0}), "K353:1 S");
    SAYS(EVS({2000, EV_KEY, BTN_MOUSE, 0}, {2000, EV_SYN, SYN_REPORT, 0}), "K353:0 S");
    /* With a modifier the driver sends BTN_RIGHT: the click too. */
    SAYS(EVS({3000, EV_KEY, BTN_RIGHT, 1}, {3000, EV_SYN, SYN_REPORT, 0}), "K353:1 S");
    /* The keyboard's own Enter stays Enter. */
    SAYS(EVS({4000, EV_KEY, KEY_ENTER, 1}, {4000, EV_SYN, SYN_REPORT, 0}), "K28:1 S");
}

static void counts_to_arrows(void) {
    start(0);
    /* A report of counts alone: no report of its own, the steps each in theirs. */
    SAYS(EVS({1000, EV_REL, REL_X, 30}, {1000, EV_REL, REL_Y, 5}, {1000, EV_SYN, SYN_REPORT, 0}), "");
    SAYS(EVS({9000, EV_REL, REL_X, 30}, {9000, EV_SYN, SYN_REPORT, 0}), "K106:1 S K106:0 S");
    SAYS(EVS({17000, EV_REL, REL_Y, -125}, {17000, EV_SYN, SYN_REPORT, 0}), "K103:1 S K103:0 S K103:1 S K103:0 S");
    /* A key and counts in one report: the key's report first, then the step. */
    SAYS(EVS({25000, EV_KEY, KEY_LEFTSHIFT, 1}, {25000, EV_REL, REL_X, -60}, {25000, EV_SYN, SYN_REPORT, 0}),
         "K42:1 S K105:1 S K105:0 S");
}

static void menu_short_and_long(void) {
    start(0);
    /* Down: nothing yet, its scan code dropped with it, no empty report. */
    SAYS(EVS({1000000, EV_MSC, MSC_SCAN, 17}, {1000000, EV_KEY, KEY_APPSELECT, 1}, {1000000, EV_SYN, SYN_REPORT, 0}), "");
    CHECK(strcmp(tick(1000 + TP_MENU_LONG_MS - 1), "") == 0);
    /* Up before the threshold: the menu. */
    SAYS(EVS({1120000, EV_MSC, MSC_SCAN, 17}, {1120000, EV_KEY, KEY_APPSELECT, 0}, {1120000, EV_SYN, SYN_REPORT, 0}),
         "K139:1 S K139:0 S");
    /* Held: the switcher at the threshold, nothing at the release. */
    SAYS(EVS({3000000, EV_MSC, MSC_SCAN, 17}, {3000000, EV_KEY, KEY_APPSELECT, 1}, {3000000, EV_SYN, SYN_REPORT, 0}), "");
    CHECK(strcmp(tick(3000 + TP_MENU_LONG_MS), "K580:1 S K580:0 S") == 0);
    CHECK(strcmp(tick(3000 + 2 * TP_MENU_LONG_MS), "") == 0);
    SAYS(EVS({4650000, EV_MSC, MSC_SCAN, 17}, {4650000, EV_KEY, KEY_APPSELECT, 0}, {4650000, EV_SYN, SYN_REPORT, 0}), "");
}

static void driver_arrows(void) {
    start(0);
    /* Arrow mode set again by someone, raw mode set back: the driver's arrows dropped. */
    dev.rearm_result = 1;
    SAYS(EVS({1000, EV_KEY, KEY_RIGHT, 1}, {1000, EV_KEY, KEY_RIGHT, 0}, {1000, EV_SYN, SYN_REPORT, 0}), "");
    CHECK(dev.rearms == 1);
    /* Raw mode cannot be set: they go through, the trackpad still moves. */
    dev.rearm_result = 0;
    SAYS(EVS({2000, EV_KEY, KEY_DOWN, 1}, {2000, EV_KEY, KEY_DOWN, 0}, {2000, EV_SYN, SYN_REPORT, 0}), "K108:1 K108:0 S");
}

static void dropped_reports(void) {
    start(0);
    SAYS(EVS({1000, EV_KEY, KEY_LEFTSHIFT, 1}, {1000, EV_SYN, SYN_REPORT, 0}), "K42:1 S");
    SAYS(EVS({2000, EV_KEY, BTN_MOUSE, 1}, {2000, EV_SYN, SYN_REPORT, 0}), "K353:1 S");
    SAYS(EVS({3000000, EV_KEY, KEY_APPSELECT, 1}, {3000000, EV_SYN, SYN_REPORT, 0}), "");
    /* Events lost: Shift was released meanwhile, the click is still held. Up to the next report
     * everything is dropped; then the twin releases what the real device no longer holds, and the
     * Menu key and a held scan code are forgotten, as Android's cancelAllDownKeys. */
    dev.real[BTN_MOUSE / 64] |= 1ull << (BTN_MOUSE % 64);
    SAYS(EVS({3100000, EV_REL, REL_X, 50}, {3100000, EV_SYN, SYN_DROPPED, 0}, {3100000, EV_KEY, KEY_A, 1},
             {3100000, EV_MSC, MSC_SCAN, 30}, {3100000, EV_SYN, SYN_REPORT, 0}),
         "K42:0 S");
    CHECK(strcmp(tick(3000 + 2 * TP_MENU_LONG_MS), "") == 0);
    /* The counts before the drop are forgotten too. */
    SAYS(EVS({3150000, EV_REL, REL_X, 15}, {3150000, EV_SYN, SYN_REPORT, 0}), "");
    SAYS(EVS({3160000, EV_KEY, BTN_MOUSE, 0}, {3160000, EV_SYN, SYN_REPORT, 0}), "K353:0 S");
}

/* ---- accidental touches at the bottom of the screen ---- */

static void parse_ints(void) {
    CHECK(tp_parse_int(NULL, 80, 0, 360) == 80);
    CHECK(tp_parse_int("null", 80, 0, 360) == 80);
    CHECK(tp_parse_int("", 80, 0, 360) == 80);
    CHECK(tp_parse_int("12px", 80, 0, 360) == 80);
    CHECK(tp_parse_int("0\n", 80, 0, 360) == 0);
    CHECK(tp_parse_int("120", 80, 0, 360) == 120);
    CHECK(tp_parse_int("-5", 80, 0, 360) == 0);
    CHECK(tp_parse_int("9000", 80, 0, 360) == 360);
    CHECK(tp_parse_int("99999999999999999999", 400, 0, 2000) == 2000);
}

static void bottom_px(void) {
    struct tp_touch t;
    memset(&t, 0, sizeof t);
    /* s2716b_ts: 0 to 2492 raw for 720 px, as Android scales it (0.2888, dumpsys input). */
    t.y_min = 0;
    t.y_max = 2492;
    CHECK(tp_touch_px(&t, 0) == 0);
    CHECK(tp_touch_px(&t, 2492) == 719);
    CHECK(tp_touch_px(&t, 2215) == 639);
    CHECK(tp_touch_px(&t, 2216) == 640);
}

/* The real touchscreen's slots, as EVIOCGMTSLOTS gives them. */
static struct {
    int fail, calls, slot;
    int val[TP_MT_COUNT][TP_SLOTS];
} ts;

static int fake_slots(void *ctx, int val[TP_MT_COUNT][TP_SLOTS], int *slot) {
    (void) ctx;
    ts.calls++;
    if (ts.fail) return -1;
    memcpy(val, ts.val, sizeof ts.val);
    *slot = ts.slot;
    return 0;
}

#define MT(code) ((code) - TP_MT_FIRST)
/* The codes of s2716b_ts (getevent -lp on the Q25). */
#define S2716B_HAVE                                                                                 \
    (1u << MT(ABS_MT_SLOT) | 1u << MT(ABS_MT_TOUCH_MAJOR) | 1u << MT(ABS_MT_TOUCH_MINOR) |          \
     1u << MT(ABS_MT_POSITION_X) | 1u << MT(ABS_MT_POSITION_Y) | 1u << MT(ABS_MT_TRACKING_ID))

static struct tp_touch tt;

static void touch_start(int64_t grabbed_us, int band, int delay) {
    memset(&ts, 0, sizeof ts);
    for (int s = 0; s < TP_SLOTS; s++) ts.val[MT(ABS_MT_TRACKING_ID)][s] = -1;
    /* A contact of before, lifted: its slot keeps its last values, which the driver does not send
     * again when they repeat. */
    ts.val[MT(ABS_MT_POSITION_X)][0] = 1000;
    ts.val[MT(ABS_MT_POSITION_Y)][0] = 2400;
    CHECK(tp_touch_init(&tt, (struct tp_touch_hooks) {NULL, fake_slots}, grabbed_us, 0, 2492, 10, S2716B_HAVE, band, delay) == 0);
}

/* The twin's touch events as text: "s" slot, "t" tracking id, "x" "y" position, "j" "n" major and
 * minor, "T" BTN_TOUCH, "F" BTN_TOOL_FINGER, "S" a report. */
static void show_touch(const struct tp_out *out, int n) {
    for (int i = 0; i < n; i++) {
        char one[32];
        const struct tp_out *o = &out[i];
        if (o->type == EV_ABS) {
            char c = o->code == ABS_MT_SLOT ? 's' : o->code == ABS_MT_TRACKING_ID ? 't' : o->code == ABS_MT_POSITION_X ? 'x'
                   : o->code == ABS_MT_POSITION_Y ? 'y' : o->code == ABS_MT_TOUCH_MAJOR ? 'j' : o->code == ABS_MT_TOUCH_MINOR ? 'n' : '?';
            snprintf(one, sizeof one, "%c%d ", c, o->value);
        } else if (o->type == EV_KEY) {
            snprintf(one, sizeof one, "%c%d ", o->code == BTN_TOUCH ? 'T' : o->code == BTN_TOOL_FINGER ? 'F' : '?', o->value);
        } else if (o->type == EV_SYN && o->code == SYN_REPORT) {
            snprintf(one, sizeof one, "S ");
        } else {
            snprintf(one, sizeof one, "?%d.%d ", o->type, o->code);
        }
        strcat(said, one);
    }
}

/* Feeds touch events {us, type, code, value} with the trackpad last active at [trackpad_us]. */
static const char *touch_in(int64_t trackpad_us, const int64_t (*evs)[4]) {
    struct tp_out out[TP_TOUCH_OUT_MAX];
    said[0] = 0;
    for (; evs[0][0] || evs[0][1]; evs++)
        show_touch(out, tp_touch_translate(&tt, trackpad_us, evs[0][0], (int) evs[0][1], (int) evs[0][2], (int) evs[0][3], out,
                                           TP_TOUCH_OUT_MAX));
    size_t n = strlen(said);
    if (n) said[n - 1] = 0;
    return said;
}

#define TSAYS(trackpad_us, events, expected)                                                  \
    do {                                                                                      \
        static const int64_t evs_[][4] = events;                                              \
        const char *got_ = touch_in(trackpad_us, evs_);                                       \
        if (strcmp(got_, expected) != 0) {                                                    \
            fprintf(stderr, "%s:%d: got \"%s\", expected \"%s\"\n", __FILE__, __LINE__, got_, \
                    expected);                                                                \
            failures++;                                                                       \
        }                                                                                     \
    } while (0)

/* A contact's first report in the driver's order (Synaptics DSX), then its lift. */
#define DOWN(us, slot, tid, x, y)                                                                                   \
    {us, EV_ABS, ABS_MT_SLOT, slot}, {us, EV_ABS, ABS_MT_TRACKING_ID, tid}, {us, EV_ABS, ABS_MT_POSITION_X, x},     \
        {us, EV_ABS, ABS_MT_POSITION_Y, y}, {us, EV_ABS, ABS_MT_TOUCH_MAJOR, 9}, {us, EV_KEY, BTN_TOUCH, 1},       \
        {us, EV_KEY, BTN_TOOL_FINGER, 1}, {us, EV_SYN, SYN_REPORT, 0}
#define UP(us, slot)                                                                                   \
    {us, EV_ABS, ABS_MT_SLOT, slot}, {us, EV_ABS, ABS_MT_TRACKING_ID, -1}, {us, EV_KEY, BTN_TOUCH, 0}, \
        {us, EV_KEY, BTN_TOOL_FINGER, 0}, {us, EV_SYN, SYN_REPORT, 0}

static void touch_passes(void) {
    touch_start(1000, 80, 400);
    /* No trackpad yet: a contact anywhere passes, its whole state, then its moves, then its lift. */
    TSAYS(-1, EVS(DOWN(2000, 0, 7, 300, 2450)), "s0 t7 j9 n0 x300 y2450 T1 F1 S");
    TSAYS(-1, EVS({3000, EV_ABS, ABS_MT_POSITION_Y, 2440}, {3000, EV_SYN, SYN_REPORT, 0}), "s0 y2440 S");
    TSAYS(-1, EVS(UP(4000, 0)), "s0 t-1 T0 F0 S");
    /* Events of before the grab: Android had them. */
    touch_start(5000, 80, 400);
    TSAYS(-1, EVS(DOWN(4999, 0, 7, 300, 300)), "");
}

static void touch_rejected_in_band(void) {
    touch_start(0, 80, 400);
    /* The trackpad moved 100 ms ago, a contact starts 27 px from the bottom: nothing of it, ever,
     * not even once the delay has passed and it moves up. */
    TSAYS(1000000, EVS(DOWN(1100000, 0, 7, 300, 2400)), "");
    CHECK(tt.rejected == 1);
    CHECK(tt.last_px == 693);
    CHECK(tt.last_after_ms == 100);
    TSAYS(1000000, EVS({2000000, EV_ABS, ABS_MT_POSITION_Y, 1200}, {2000000, EV_SYN, SYN_REPORT, 0}), "");
    TSAYS(1000000, EVS(UP(2100000, 0)), "");
    /* The next contact, after the delay, passes. */
    TSAYS(1000000, EVS(DOWN(2200000, 0, 8, 300, 2400)), "s0 t8 j9 n0 x300 y2400 T1 F1 S");
}

static void touch_edges(void) {
    touch_start(0, 80, 400);
    /* Just above the band (639 px): passes. */
    TSAYS(1000000, EVS(DOWN(1100000, 0, 7, 300, 2215)), "s0 t7 j9 n0 x300 y2215 T1 F1 S");
    TSAYS(1000000, EVS(UP(1100100, 0)), "s0 t-1 T0 F0 S");
    /* First line of the band, 399 ms after: ignored. */
    TSAYS(1000000, EVS(DOWN(1399000, 0, 8, 300, 2216)), "");
    TSAYS(1000000, EVS(UP(1399100, 0)), "");
    /* 400 ms after: passes. */
    TSAYS(1000000, EVS(DOWN(1400000, 0, 9, 300, 2216)), "s0 t9 j9 n0 x300 y2216 T1 F1 S");
    TSAYS(1000000, EVS(UP(1400100, 0)), "s0 t-1 T0 F0 S");
    /* The trackpad read after the contact (the two devices are read in either order): ignored. */
    TSAYS(1500000, EVS(DOWN(1499000, 0, 10, 300, 2300)), "");
}

static void touch_never_cut(void) {
    touch_start(0, 80, 400);
    /* A contact started before the trackpad moves on into the band while the trackpad moves. */
    TSAYS(-1, EVS(DOWN(1000000, 0, 7, 300, 1000)), "s0 t7 j9 n0 x300 y1000 T1 F1 S");
    TSAYS(1100000, EVS({1100000, EV_ABS, ABS_MT_POSITION_Y, 2450}, {1100000, EV_SYN, SYN_REPORT, 0}), "s0 y2450 S");
    /* A second finger in the band meanwhile: dropped alone, the first one kept, BTN_TOUCH held. */
    TSAYS(1100000, EVS(DOWN(1150000, 1, 8, 500, 2400)), "");
    TSAYS(1100000,
          EVS({1160000, EV_ABS, ABS_MT_SLOT, 0}, {1160000, EV_ABS, ABS_MT_POSITION_X, 310}, {1160000, EV_ABS, ABS_MT_SLOT, 1},
              {1160000, EV_ABS, ABS_MT_POSITION_X, 520}, {1160000, EV_SYN, SYN_REPORT, 0}),
          "s0 x310 S");
    /* The first lifts: the twin's touch ends, though the ignored one stays down. */
    TSAYS(1100000, EVS({1200000, EV_ABS, ABS_MT_SLOT, 0}, {1200000, EV_ABS, ABS_MT_TRACKING_ID, -1}, {1200000, EV_SYN, SYN_REPORT, 0}),
          "s0 t-1 T0 F0 S");
    TSAYS(1100000, EVS(UP(1300000, 1)), "");
}

static void touch_band_off(void) {
    /* 0 for the band or the delay: nothing ignored. */
    touch_start(0, 0, 400);
    TSAYS(1000000, EVS(DOWN(1100000, 0, 7, 300, 2450)), "s0 t7 j9 n0 x300 y2450 T1 F1 S");
    touch_start(0, 80, 0);
    TSAYS(1000000, EVS(DOWN(1100000, 0, 7, 300, 2450)), "s0 t7 j9 n0 x300 y2450 T1 F1 S");
    /* Set live: from the next contact on. */
    touch_start(0, 80, 400);
    tp_touch_set(&tt, 0, 400);
    TSAYS(1000000, EVS(DOWN(1100000, 0, 7, 300, 2450)), "s0 t7 j9 n0 x300 y2450 T1 F1 S");
    tp_touch_set(&tt, 200, 400);
    TSAYS(1000000, EVS(DOWN(1100000, 1, 8, 300, 1900)), "");
}

static void touch_repeated_values(void) {
    touch_start(0, 80, 400);
    /* A contact where the one of before was (x 1000, y 2400): the driver sends no position, the
     * twin gets the slot's whole state. */
    TSAYS(-1, EVS({1000, EV_ABS, ABS_MT_SLOT, 0}, {1000, EV_ABS, ABS_MT_TRACKING_ID, 7}, {1000, EV_KEY, BTN_TOUCH, 1}, {1000, EV_SYN, SYN_REPORT, 0}),
          "s0 t7 j0 n0 x1000 y2400 T1 F1 S");
    /* The driver keeps its slot between reports: a move without ABS_MT_SLOT is slot 0's. */
    TSAYS(-1, EVS({2000, EV_ABS, ABS_MT_POSITION_X, 990}, {2000, EV_SYN, SYN_REPORT, 0}), "s0 x990 S");
}

static void touch_new_contact_on_a_kept_slot(void) {
    touch_start(0, 80, 400);
    TSAYS(-1, EVS(DOWN(1000, 0, 7, 300, 1000)), "s0 t7 j9 n0 x300 y1000 T1 F1 S");
    /* A new tracking id on the same slot, in the band, after the trackpad: the twin's contact ends,
     * the new one is ignored. */
    TSAYS(2000, EVS({3000, EV_ABS, ABS_MT_TRACKING_ID, 8}, {3000, EV_ABS, ABS_MT_POSITION_Y, 2450}, {3000, EV_SYN, SYN_REPORT, 0}),
          "s0 t-1 T0 F0 S");
}

static void touch_dropped(void) {
    touch_start(0, 80, 400);
    TSAYS(-1, EVS(DOWN(1000, 0, 7, 300, 1000)), "s0 t7 j9 n0 x300 y1000 T1 F1 S");
    TSAYS(-1, EVS(DOWN(2000, 1, 8, 500, 1000)), "s1 t8 j9 n0 x500 y1000 S");
    /* Events lost; meanwhile slot 0 lifted, slot 1 moved, a contact started in slot 2 in the band
     * after the trackpad. Up to the next report everything is dropped, then the slots are read
     * again: the twin follows them, the new one judged as any start. */
    for (int s = 0; s < TP_SLOTS; s++) ts.val[MT(ABS_MT_TRACKING_ID)][s] = -1;
    ts.val[MT(ABS_MT_TRACKING_ID)][1] = 8;
    ts.val[MT(ABS_MT_POSITION_X)][1] = 600;
    ts.val[MT(ABS_MT_POSITION_Y)][1] = 1000;
    ts.val[MT(ABS_MT_TOUCH_MAJOR)][1] = 9;
    ts.val[MT(ABS_MT_TRACKING_ID)][2] = 9;
    ts.val[MT(ABS_MT_POSITION_Y)][2] = 2450;
    ts.slot = 2;
    TSAYS(2900000,
          EVS({3000000, EV_ABS, ABS_MT_POSITION_X, 590}, {3000000, EV_SYN, SYN_DROPPED, 0}, {3000000, EV_ABS, ABS_MT_POSITION_X, 595},
              {3000000, EV_SYN, SYN_REPORT, 0}),
          "s0 t-1 s1 j9 n0 x600 y1000 S");
    CHECK(tt.rejected == 1);
    /* The driver's slot is the one read: a move without ABS_MT_SLOT is slot 2's, ignored. */
    TSAYS(-1, EVS({3100000, EV_ABS, ABS_MT_POSITION_X, 100}, {3100000, EV_SYN, SYN_REPORT, 0}), "");
    /* Unreadable: every contact of the twin ends, the real ones are waited out. */
    ts.fail = 1;
    TSAYS(-1, EVS({4000000, EV_SYN, SYN_DROPPED, 0}, {4000000, EV_SYN, SYN_REPORT, 0}), "s1 t-1 T0 F0 S");
    TSAYS(-1, EVS({4100000, EV_ABS, ABS_MT_SLOT, 1}, {4100000, EV_ABS, ABS_MT_POSITION_X, 700}, {4100000, EV_SYN, SYN_REPORT, 0}), "");
}

static void touch_init_fails(void) {
    memset(&ts, 0, sizeof ts);
    ts.fail = 1;
    CHECK(tp_touch_init(&tt, (struct tp_touch_hooks) {NULL, fake_slots}, 0, 0, 2492, 10, S2716B_HAVE, 80, 400) < 0);
    /* More slots than the model keeps, or no position: refused. */
    ts.fail = 0;
    CHECK(tp_touch_init(&tt, (struct tp_touch_hooks) {NULL, fake_slots}, 0, 0, 2492, TP_SLOTS + 1, S2716B_HAVE, 80, 400) < 0);
    CHECK(tp_touch_init(&tt, (struct tp_touch_hooks) {NULL, fake_slots}, 0, 0, 0, 10, S2716B_HAVE, 80, 400) < 0);
    CHECK(tp_touch_init(&tt, (struct tp_touch_hooks) {NULL, fake_slots}, 0, 0, 2492, 10, 1u << MT(ABS_MT_POSITION_Y), 80, 400) < 0);
}

static void trackpad_activity(void) {
    start(0);
    CHECK(tr.trackpad_us == -1);
    /* Counts, even without a step, and the click mark the trackpad active; a key does not. */
    SAYS(EVS({1000, EV_REL, REL_X, 3}, {1000, EV_SYN, SYN_REPORT, 0}), "");
    CHECK(tr.trackpad_us == 1000);
    SAYS(EVS({2000, EV_KEY, KEY_A, 1}, {2000, EV_SYN, SYN_REPORT, 0}), "K30:1 S");
    CHECK(tr.trackpad_us == 1000);
    SAYS(EVS({3000, EV_KEY, BTN_MOUSE, 1}, {3000, EV_SYN, SYN_REPORT, 0}), "K353:1 S");
    CHECK(tr.trackpad_us == 3000);
    SAYS(EVS({4000, EV_KEY, BTN_MOUSE, 0}, {4000, EV_SYN, SYN_REPORT, 0}), "K353:0 S");
    CHECK(tr.trackpad_us == 4000);
    /* The driver's own arrows (arrow mode set again by someone) too. */
    dev.rearm_result = 0;
    SAYS(EVS({5000, EV_KEY, KEY_DOWN, 1}, {5000, EV_SYN, SYN_REPORT, 0}), "K108:1 S");
    CHECK(tr.trackpad_us == 5000);
    /* Events of before the grab do not count. */
    start(9000);
    SAYS(EVS({8000, EV_REL, REL_X, 3}, {8000, EV_SYN, SYN_REPORT, 0}), "");
    CHECK(tr.trackpad_us == -1);
}

int main(void) {
    parse_ints();
    bottom_px();
    touch_passes();
    touch_rejected_in_band();
    touch_edges();
    touch_never_cut();
    touch_band_off();
    touch_repeated_values();
    touch_new_contact_on_a_kept_slot();
    touch_dropped();
    touch_init_fails();
    trackpad_activity();
    keys_pass();
    before_grab_dropped();
    click_is_select();
    counts_to_arrows();
    menu_short_and_long();
    driver_arrows();
    dropped_reports();
    menu_key();
    parse();
    thresholds();
    steps_and_rest();
    max_steps();
    pause_forgets();
    turn_back();
    one_axis();
    per_axis_sensitivity();
    settings_change();
    if (failures) {
        fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    printf("trackpad model: all tests passed\n");
    return 0;
}
