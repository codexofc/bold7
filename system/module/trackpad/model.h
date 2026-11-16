/*
 * The trackpad of BlackBerry OS 7.1, pure: raw counts of the Q25's trackpad in, focus steps out.
 * No I/O, tested on the Mac by test_model.c.
 *
 * The model is that of OS 7's Trackball API (net.rim.device.api.ui.Trackball, 7.1 Javadoc, M):
 * one count per axis; a step when an axis passes its threshold, the higher the sensitivity the
 * lower the threshold; the counts forgotten after a pause ("so a small nudge won't trigger an
 * event"); one axis at a time (FILTER_XY_SNAP). The rest past a threshold is kept, a turn back on
 * an axis starts it again from zero.
 */
#ifndef BOLD7_TRACKPAD_MODEL_H
#define BOLD7_TRACKPAD_MODEL_H

#include <stdint.h>

/* The Options screen's values (Trackpad Sensitivity, sim capture, C): 20 to 100 by 10, 70 by default. */
#define TP_SENS_MIN 20
#define TP_SENS_MAX 100
#define TP_SENS_DEFAULT 70

/*
 * Eyeballed on a measure on the Q25 (docs/DEVICE.md, "Trackpad"): counts per step at 70.
 * A full sweep of the pad is 200 to 240 counts: 3 to 4 steps at 70, where the driver's arrows
 * (27 counts, the rest lost) gave 5 to 6. The firmware's own law is not readable (compiled in
 * the .cod files).
 */
#define TP_THRESHOLD_70 60
/* Eyeballed: a pause longer than this forgets the counts (in motion, reads come every 7.5 ms). */
#define TP_WINDOW_MS 200

enum tp_dir { TP_UP, TP_DOWN, TP_LEFT, TP_RIGHT };

struct tp_model {
    int th_x, th_y;   /* counts per step on each axis */
    int acc_x, acc_y; /* counts since the last step, or the last pause */
    int64_t last_ms;  /* time of the last read, -1 before the first */
};

/* A setting as `settings get` prints it: absent, "null" or not a number gives [def]; clamped to [min]..[max]. */
int tp_parse_int(const char *value, int def, int min, int max);

/* A sensitivity setting: tp_parse_int with 70, 20 and 100. */
int tp_parse_sensitivity(const char *value);

/* Counts per step for a sensitivity: inversely proportional, TP_THRESHOLD_70 at 70 (eyeballed law). */
int tp_threshold(int sensitivity);

void tp_init(struct tp_model *m, int horizontal, int vertical);

/* New sensitivities, the counts kept. */
void tp_set_sensitivity(struct tp_model *m, int horizontal, int vertical);

/*
 * One report of the trackpad, [dx] [dy] counts at [ms] (monotonic). Writes at most [max] steps to
 * [out] and returns their number.
 */
int tp_feed(struct tp_model *m, int64_t ms, int dx, int dy, enum tp_dir *out, int max);

/*
 * The Q25's Menu key (BlackBerry logo, left of the trackpad) as the 9900's: pressed briefly, the
 * menu (KEY_MENU) at the release; held, the app switcher (KEY_APPSELECT, Android's APP_SWITCH) as
 * soon as it has been held TP_MENU_LONG_MS, nothing more at the release (user guide 7.1, "Important
 * keys": Menu "Open a menu", "Press and hold to switch applications quickly", B). The key itself sends KEY_APPSELECT, once down and
 * once up, never a repeat (measured on the Q25, docs/DEVICE.md).
 */
/* D: the 9900's threshold is not known; Android's long press (ViewConfiguration, long_press_timeout
 * of the Q25: 400 ms). */
#define TP_MENU_LONG_MS 400

enum tp_menu_out { TP_MENU_NONE, TP_MENU_SHORT, TP_MENU_LONG };

struct tp_menu {
    int down, fired;
    int64_t down_ms;
};

void tp_menu_down(struct tp_menu *k, int64_t ms);
/* At the release: SHORT, or LONG if its time passed unseen, NONE if LONG was already sent. */
enum tp_menu_out tp_menu_up(struct tp_menu *k, int64_t ms);
/* When the deadline comes: LONG once. */
enum tp_menu_out tp_menu_tick(struct tp_menu *k, int64_t now);
/* When to tick next (monotonic ms), -1 for never. */
int64_t tp_menu_deadline(const struct tp_menu *k);

/*
 * The translation of what the real device sends into what the twin sends (bold7_trackpad.c's
 * worker), pure: the event loop's decisions, tested on the Mac. The kernel's event and key codes
 * (linux/input-event-codes.h, a fixed ABI) are named here when the Mac's headers lack them.
 */
#ifndef EV_SYN
#define EV_SYN 0x00
#define EV_KEY 0x01
#define EV_REL 0x02
#define EV_MSC 0x04
#define SYN_REPORT 0
#define SYN_DROPPED 3
#define REL_X 0x00
#define REL_Y 0x01
#define MSC_SCAN 0x04
#define KEY_ENTER 28
#define KEY_SELECT 353 /* the click: DPAD_CENTER in Q25_keyboard.kl, not Enter (sim-97b) */
#define KEY_A 30
#define KEY_LEFTSHIFT 42
#define KEY_UP 103
#define KEY_LEFT 105
#define KEY_RIGHT 106
#define KEY_DOWN 108
#define KEY_MENU 139
#define KEY_BACK 158
#define BTN_MOUSE 0x110
#define BTN_RIGHT 0x111
#define KEY_APPSELECT 0x244
#define KEY_MAX 0x2ff
#define EV_ABS 0x03
#define BTN_TOOL_FINGER 0x145
#define BTN_TOUCH 0x14a
#define ABS_MT_SLOT 0x2f
#define ABS_MT_TOUCH_MAJOR 0x30
#define ABS_MT_TOUCH_MINOR 0x31
#define ABS_MT_POSITION_X 0x35
#define ABS_MT_POSITION_Y 0x36
#define ABS_MT_TRACKING_ID 0x39
#endif

#define TP_KEY_WORDS (KEY_MAX / 64 + 1)

/* An event for the twin. */
struct tp_out {
    int type, code, value;
};

/* What the translation asks of the device, faked by the tests. */
struct tp_hooks {
    void *ctx;
    /* The driver sent arrows: someone put it back in arrow mode. Returns 1 when raw mode is set
     * again (its arrows are then dropped), 0 otherwise (they go through). */
    int (*rearm)(void *ctx);
    /* The real device's keys down now (EVIOCGKEY), one bit per code; 0 on success. */
    int (*real_keys)(void *ctx, uint64_t *bits);
};

struct tp_translator {
    struct tp_hooks hooks;
    struct tp_model model;
    struct tp_menu menu;
    int64_t grabbed_us;          /* events from before the grab went to Android: dropped */
    int dx, dy;                  /* counts of the report being read */
    int pending;                 /* events passed on, their SYN_REPORT not yet */
    int has_msc, msc_value;      /* a scan code held back until its key is known */
    int resync;                  /* after SYN_DROPPED: everything dropped up to the next report */
    unsigned dropped_arrows;     /* the driver's arrows dropped while down, one bit per arrow */
    uint64_t down[TP_KEY_WORDS]; /* the twin's keys down */
    int64_t trackpad_us;         /* the trackpad's last count, click or arrow (tp_touch), -1 before any */
};

void tp_translator_init(struct tp_translator *t, struct tp_hooks hooks, int64_t grabbed_us, int horizontal, int vertical);

/* One event read from the real device, at [us] (its monotonic time). Writes at most [max] events
 * for the twin to [out] and returns their number. */
int tp_translate(struct tp_translator *t, int64_t us, int type, int code, int value, struct tp_out *out, int max);

/* At [now_ms], with nothing read: the Menu key held past its threshold. Same output as tp_translate. */
int tp_translate_tick(struct tp_translator *t, int64_t now_ms, struct tp_out *out, int max);

/*
 * Accidental touches. The thumb that pushes the trackpad up touches
 * the bottom of the screen, just above it, and Android takes that for a tap. A contact that STARTS
 * in a band at the bottom of the screen less than a delay after the trackpad's last activity
 * (tp_translator.trackpad_us) is dropped for its whole life; any other contact, and any contact
 * already down when the trackpad moves, passes untouched (a gesture is never cut). The touchscreen
 * (s2716b_ts, multi-touch type B) is read whole and its twin is given the state of the contacts it
 * keeps: their slot, tracking id and axes, and BTN_TOUCH and BTN_TOOL_FINGER from them.
 */
/* D (DEVICE.md: 720x720): the screen's height, the band's unit. */
#define TP_SCREEN_PX 720
/* D, eyeballed: about 7 mm (295 ppi) above the trackpad, most of the home screen's tray row (97 px). */
#define TP_PALM_BAND_DEFAULT 80
#define TP_PALM_BAND_MAX (TP_SCREEN_PX / 2)
/* D, eyeballed: as Android's long press on the Q25; in motion the trackpad reads every 7.5 ms. */
#define TP_PALM_DELAY_DEFAULT 400
#define TP_PALM_DELAY_MAX 2000

/* The multi-touch codes, ABS_MT_SLOT (0x2f) to ABS_MT_TOOL_Y (0x3d), one bit or index each. */
#define TP_MT_FIRST 0x2f
#define TP_MT_COUNT 15
/* s2716b_ts has 10. */
#define TP_SLOTS 16
/* The most one report can give the twin: per slot, its slot, an end and a start with every code;
 * then the two keys and the report. */
#define TP_TOUCH_OUT_MAX (TP_SLOTS * (TP_MT_COUNT + 2) + 3)

struct tp_touch_hooks {
    void *ctx;
    /* The real device's values now (EVIOCGMTSLOTS for each code, [code - TP_MT_FIRST][slot]) and
     * its current slot; 0 on success. */
    int (*real_slots)(void *ctx, int val[TP_MT_COUNT][TP_SLOTS], int *slot);
};

struct tp_touch {
    struct tp_touch_hooks hooks;
    int64_t grabbed_us;   /* events from before the grab went to Android: dropped */
    int y_min, y_max;     /* ABS_MT_POSITION_Y's range */
    int slots;            /* the device's slots */
    unsigned have;        /* the device's multi-touch codes, bit (code - TP_MT_FIRST) */
    int band_px, delay_ms;
    int slot;             /* the real device's current slot */
    int val[TP_MT_COUNT][TP_SLOTS]; /* the real device's values, as the kernel keeps them */
    unsigned dirty[TP_SLOTS];       /* codes changed in the report being read */
    int twin_tid[TP_SLOTS];         /* the contact the twin has on each slot, -1 none */
    int dropped_tid[TP_SLOTS];      /* the contact ignored on each slot, -1 none */
    int touching;                   /* the twin's BTN_TOUCH */
    int resync;                     /* after SYN_DROPPED: everything dropped up to the next report */
    unsigned rejected;              /* contacts ignored so far, the last one's place and time: */
    int last_px, last_after_ms;     /* for the log */
};

/* Reads the real slots (the device must be grabbed); -1 when they cannot be read, when the device
 * has more than TP_SLOTS slots, no position or no tracking id. */
int tp_touch_init(struct tp_touch *t, struct tp_touch_hooks hooks, int64_t grabbed_us, int y_min, int y_max, int slots,
                  unsigned have, int band_px, int delay_ms);

/* New band and delay, from the next contact on; 0 for either: nothing ignored. */
void tp_touch_set(struct tp_touch *t, int band_px, int delay_ms);

/* A raw y in screen pixels from the top, as Android scales it. */
int tp_touch_px(const struct tp_touch *t, int y);

/* One event read from the real touchscreen at [us], the trackpad last active at [trackpad_us]
 * (-1: never). Writes at most [max] events (TP_TOUCH_OUT_MAX is always enough) for the twin to
 * [out] and returns their number. */
int tp_touch_translate(struct tp_touch *t, int64_t trackpad_us, int64_t us, int type, int code, int value, struct tp_out *out,
                       int max);

#endif
