#include "model.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

int tp_parse_int(const char *value, int def, int min, int max) {
    if (value == NULL) return def;
    char *end;
    errno = 0;
    long s = strtol(value, &end, 10);
    if (end == value) return def;
    while (*end == '\n' || *end == '\r' || *end == ' ') end++;
    if (*end != 0) return def;
    if (errno == ERANGE) return s < 0 ? min : max;
    if (s < min) return min;
    if (s > max) return max;
    return (int) s;
}

int tp_parse_sensitivity(const char *value) {
    return tp_parse_int(value, TP_SENS_DEFAULT, TP_SENS_MIN, TP_SENS_MAX);
}

int tp_threshold(int sensitivity) {
    if (sensitivity < TP_SENS_MIN) sensitivity = TP_SENS_MIN;
    if (sensitivity > TP_SENS_MAX) sensitivity = TP_SENS_MAX;
    return (TP_THRESHOLD_70 * 70 + sensitivity / 2) / sensitivity;
}

void tp_init(struct tp_model *m, int horizontal, int vertical) {
    m->acc_x = m->acc_y = 0;
    m->last_ms = -1;
    tp_set_sensitivity(m, horizontal, vertical);
}

void tp_set_sensitivity(struct tp_model *m, int horizontal, int vertical) {
    m->th_x = tp_threshold(horizontal);
    m->th_y = tp_threshold(vertical);
}

/* [acc] plus [d], or [d] alone when it turns back. */
static int add(int acc, int d) {
    if ((d > 0 && acc < 0) || (d < 0 && acc > 0)) return d;
    return acc + d;
}

int tp_feed(struct tp_model *m, int64_t ms, int dx, int dy, enum tp_dir *out, int max) {
    if (m->last_ms >= 0 && ms - m->last_ms > TP_WINDOW_MS) m->acc_x = m->acc_y = 0;
    m->last_ms = ms;
    m->acc_x = add(m->acc_x, dx);
    m->acc_y = add(m->acc_y, dy);
    int ax = abs(m->acc_x), ay = abs(m->acc_y);
    if (ax < m->th_x && ay < m->th_y) return 0;
    /* The axis furthest past its own threshold, compared as ax / th_x >= ay / th_y. */
    int horizontal = (int64_t) ax * m->th_y >= (int64_t) ay * m->th_x;
    int *acc = horizontal ? &m->acc_x : &m->acc_y;
    int th = horizontal ? m->th_x : m->th_y;
    int sign = *acc > 0 ? 1 : -1;
    enum tp_dir dir = horizontal ? (sign > 0 ? TP_RIGHT : TP_LEFT) : (sign > 0 ? TP_DOWN : TP_UP);
    int n = abs(*acc) / th;
    if (n > max) n = max;
    for (int i = 0; i < n; i++) out[i] = dir;
    *acc -= sign * n * th;
    /* One axis at a time: the other's counts are forgotten. */
    if (horizontal) m->acc_y = 0; else m->acc_x = 0;
    return n;
}

void tp_menu_down(struct tp_menu *k, int64_t ms) {
    k->down = 1;
    k->fired = 0;
    k->down_ms = ms;
}

enum tp_menu_out tp_menu_tick(struct tp_menu *k, int64_t now) {
    if (!k->down || k->fired || now - k->down_ms < TP_MENU_LONG_MS) return TP_MENU_NONE;
    k->fired = 1;
    return TP_MENU_LONG;
}

enum tp_menu_out tp_menu_up(struct tp_menu *k, int64_t ms) {
    if (!k->down) return TP_MENU_NONE;
    enum tp_menu_out out = k->fired ? TP_MENU_NONE : tp_menu_tick(k, ms) == TP_MENU_LONG ? TP_MENU_LONG : TP_MENU_SHORT;
    k->down = 0;
    return out;
}

int64_t tp_menu_deadline(const struct tp_menu *k) {
    return k->down && !k->fired ? k->down_ms + TP_MENU_LONG_MS : -1;
}

/* ---- the translation of the event loop ---- */

struct sink {
    struct tp_translator *t;
    struct tp_out *out;
    int n, max;
};

static void put(struct sink *s, int type, int code, int value) {
    if (s->n == s->max) return;
    s->out[s->n++] = (struct tp_out) {type, code, value};
    if (type == EV_KEY && code >= 0 && code <= KEY_MAX) {
        uint64_t bit = 1ull << (code % 64);
        if (value) s->t->down[code / 64] |= bit; else s->t->down[code / 64] &= ~bit;
    }
}

/* A key pressed and released at once, each in its own report. */
static void tap(struct sink *s, int code) {
    put(s, EV_KEY, code, 1);
    put(s, EV_SYN, SYN_REPORT, 0);
    put(s, EV_KEY, code, 0);
    put(s, EV_SYN, SYN_REPORT, 0);
}

static void menu_out(struct sink *s, enum tp_menu_out out) {
    if (out == TP_MENU_SHORT) tap(s, KEY_MENU);
    if (out == TP_MENU_LONG) tap(s, KEY_APPSELECT);
}

static void flush_msc(struct sink *s) {
    if (!s->t->has_msc) return;
    put(s, EV_MSC, MSC_SCAN, s->t->msc_value);
    s->t->has_msc = 0;
    s->t->pending = 1;
}

static int is_down(const uint64_t *bits, int code) {
    return (bits[code / 64] >> (code % 64)) & 1;
}

void tp_translator_init(struct tp_translator *t, struct tp_hooks hooks, int64_t grabbed_us, int horizontal, int vertical) {
    memset(t, 0, sizeof *t);
    t->hooks = hooks;
    t->grabbed_us = grabbed_us;
    t->trackpad_us = -1;
    tp_init(&t->model, horizontal, vertical);
}

/* After lost events (SYN_DROPPED): the twin releases each key the real device no longer holds,
 * the click's KEY_SELECT staying down while the click does. Unreadable, every key is released. */
static void resync(struct sink *s) {
    struct tp_translator *t = s->t;
    uint64_t real[TP_KEY_WORDS];
    if (t->hooks.real_keys(t->hooks.ctx, real) != 0) memset(real, 0, sizeof real);
    if (is_down(real, BTN_MOUSE) || is_down(real, BTN_RIGHT)) real[KEY_SELECT / 64] |= 1ull << (KEY_SELECT % 64);
    int released = 0;
    for (int code = 0; code <= KEY_MAX; code++)
        if (is_down(t->down, code) && !is_down(real, code)) {
            put(s, EV_KEY, code, 0);
            released = 1;
        }
    if (released) put(s, EV_SYN, SYN_REPORT, 0);
}

int tp_translate(struct tp_translator *t, int64_t us, int type, int code, int value, struct tp_out *out, int max) {
    struct sink s = {t, out, 0, max};
    if (us < t->grabbed_us) return 0;
    if (type == EV_SYN && code == SYN_DROPPED) {
        /* Reads lost: drop up to the next report, forget the counts, the Menu key and a held
         * scan code, as Android's cancelAllDownKeys. */
        t->resync = 1;
        t->dx = t->dy = t->pending = t->has_msc = 0;
        t->model.acc_x = t->model.acc_y = 0;
        t->model.last_ms = -1;
        memset(&t->menu, 0, sizeof t->menu);
        return 0;
    }
    if (t->resync) {
        if (type == EV_SYN && code == SYN_REPORT) {
            t->resync = 0;
            resync(&s);
        }
        return s.n;
    }
    /* The trackpad's activity, for the touches at the bottom of the screen (tp_touch). */
    if (type == EV_REL || (type == EV_KEY && (code == BTN_MOUSE || code == BTN_RIGHT || code == KEY_UP || code == KEY_DOWN ||
                                              code == KEY_LEFT || code == KEY_RIGHT)))
        t->trackpad_us = us;
    switch (type) {
    case EV_REL:
        if (code == REL_X) t->dx += value;
        if (code == REL_Y) t->dy += value;
        break;
    case EV_MSC:
        flush_msc(&s);
        t->has_msc = 1;
        t->msc_value = value;
        break;
    case EV_KEY:
        if (code == KEY_APPSELECT) {
            /* The Menu key: its scan code goes with it, the outcome is the 9900's. */
            t->has_msc = 0;
            if (value == 1) tp_menu_down(&t->menu, us / 1000);
            else if (value == 0) menu_out(&s, tp_menu_up(&t->menu, us / 1000));
            break;
        }
        flush_msc(&s);
        if (code == BTN_MOUSE || code == BTN_RIGHT) code = KEY_SELECT;
        if (code == KEY_UP || code == KEY_DOWN || code == KEY_LEFT || code == KEY_RIGHT) {
            /* Arrows from the driver: someone put it back in arrow mode (Q25TrackpadCustomizer,
             * a resume). Raw mode again and its arrows dropped; passed on if that fails. */
            unsigned bit = 1u << (code - KEY_UP);
            if (value == 1 && t->hooks.rearm(t->hooks.ctx)) t->dropped_arrows |= bit;
            if (t->dropped_arrows & bit) {
                if (value == 0) t->dropped_arrows &= ~bit;
                break;
            }
        }
        put(&s, EV_KEY, code, value);
        t->pending = 1;
        break;
    case EV_SYN:
        if (code != SYN_REPORT) break;
        flush_msc(&s);
        /* The keys of this report first, closed by its own report; then the trackpad's steps. */
        if (t->pending) put(&s, EV_SYN, SYN_REPORT, 0);
        t->pending = 0;
        if (t->dx || t->dy) {
            enum tp_dir steps[8];
            static const int keys[] = {[TP_UP] = KEY_UP, [TP_DOWN] = KEY_DOWN, [TP_LEFT] = KEY_LEFT, [TP_RIGHT] = KEY_RIGHT};
            int n = tp_feed(&t->model, us / 1000, t->dx, t->dy, steps, 8);
            t->dx = t->dy = 0;
            for (int i = 0; i < n; i++) tap(&s, keys[steps[i]]);
        }
        break;
    }
    return s.n;
}

int tp_translate_tick(struct tp_translator *t, int64_t now_ms, struct tp_out *out, int max) {
    struct sink s = {t, out, 0, max};
    menu_out(&s, tp_menu_tick(&t->menu, now_ms));
    return s.n;
}

/* ---- accidental touches at the bottom of the screen ---- */

#define BIT(code) (1u << ((code) - TP_MT_FIRST))
#define VAL(t, code, s) ((t)->val[(code) - TP_MT_FIRST][s])

/* The touch twin's events: no keys to keep, unlike the keyboard's (put). */
static void put_touch(struct sink *s, int type, int code, int value) {
    if (s->n < s->max) s->out[s->n++] = (struct tp_out) {type, code, value};
}

int tp_touch_init(struct tp_touch *t, struct tp_touch_hooks hooks, int64_t grabbed_us, int y_min, int y_max, int slots,
                  unsigned have, int band_px, int delay_ms) {
    memset(t, 0, sizeof *t);
    if (slots < 1 || slots > TP_SLOTS || y_max <= y_min || !(have & BIT(ABS_MT_POSITION_Y)) || !(have & BIT(ABS_MT_TRACKING_ID)))
        return -1;
    t->hooks = hooks;
    t->grabbed_us = grabbed_us;
    t->y_min = y_min;
    t->y_max = y_max;
    t->slots = slots;
    t->have = have;
    tp_touch_set(t, band_px, delay_ms);
    for (int s = 0; s < TP_SLOTS; s++) t->twin_tid[s] = t->dropped_tid[s] = -1;
    return t->hooks.real_slots(t->hooks.ctx, t->val, &t->slot) == 0 ? 0 : -1;
}

void tp_touch_set(struct tp_touch *t, int band_px, int delay_ms) {
    t->band_px = band_px;
    t->delay_ms = delay_ms;
}

int tp_touch_px(const struct tp_touch *t, int y) {
    return (int) ((int64_t) (y - t->y_min) * TP_SCREEN_PX / (t->y_max - t->y_min + 1));
}

/* Whether a contact starting at [us] at raw [y] is the thumb on the trackpad. A trackpad event
 * read after the contact's but stamped later counts too (us - trackpad_us negative). */
static int accidental(struct tp_touch *t, int64_t trackpad_us, int64_t us, int y) {
    if (t->band_px <= 0 || t->delay_ms <= 0 || trackpad_us < 0) return 0;
    int64_t after_us = us - trackpad_us;
    int px = tp_touch_px(t, y);
    if (after_us >= (int64_t) t->delay_ms * 1000 || px < TP_SCREEN_PX - t->band_px) return 0;
    t->rejected++;
    t->last_px = px;
    t->last_after_ms = (int) (after_us / 1000);
    return 1;
}

/* A report's end: the twin follows the real contacts it keeps. */
static void report(struct tp_touch *t, struct sink *s, int64_t trackpad_us, int64_t us) {
    const unsigned axes = t->have & ~(BIT(ABS_MT_SLOT) | BIT(ABS_MT_TRACKING_ID));
    int touching = 0;
    for (int slot = 0; slot < t->slots; slot++) {
        int tid = VAL(t, ABS_MT_TRACKING_ID, slot);
        unsigned changed = t->dirty[slot] & axes;
        t->dirty[slot] = 0;
        if (tid >= 0 && tid == t->dropped_tid[slot]) continue;
        if (tid >= 0 && tid == t->twin_tid[slot]) {
            /* A contact the twin has: what changed. */
            if (changed) put_touch(s, EV_ABS, ABS_MT_SLOT, slot);
            for (int c = 0; c < TP_MT_COUNT; c++)
                if (changed & (1u << c)) put_touch(s, EV_ABS, TP_MT_FIRST + c, t->val[c][slot]);
            touching = 1;
            continue;
        }
        /* Lifted, or another contact on the slot: the twin's ends. */
        t->dropped_tid[slot] = -1;
        int slot_said = 0;
        if (t->twin_tid[slot] >= 0) {
            put_touch(s, EV_ABS, ABS_MT_SLOT, slot);
            put_touch(s, EV_ABS, ABS_MT_TRACKING_ID, -1);
            t->twin_tid[slot] = -1;
            slot_said = 1;
        }
        if (tid < 0) continue;
        /* A contact starts: ignored for its whole life, or given whole (the driver leaves out the
         * values that repeat those of the slot's last contact). */
        if (accidental(t, trackpad_us, us, VAL(t, ABS_MT_POSITION_Y, slot))) {
            t->dropped_tid[slot] = tid;
            continue;
        }
        if (!slot_said) put_touch(s, EV_ABS, ABS_MT_SLOT, slot);
        put_touch(s, EV_ABS, ABS_MT_TRACKING_ID, tid);
        for (int c = 0; c < TP_MT_COUNT; c++)
            if (axes & (1u << c)) put_touch(s, EV_ABS, TP_MT_FIRST + c, t->val[c][slot]);
        t->twin_tid[slot] = tid;
        touching = 1;
    }
    if (touching != t->touching) {
        put_touch(s, EV_KEY, BTN_TOUCH, touching);
        put_touch(s, EV_KEY, BTN_TOOL_FINGER, touching);
        t->touching = touching;
    }
    if (s->n) put_touch(s, EV_SYN, SYN_REPORT, 0);
}

int tp_touch_translate(struct tp_touch *t, int64_t trackpad_us, int64_t us, int type, int code, int value, struct tp_out *out,
                       int max) {
    struct sink s = {NULL, out, 0, max};
    if (us < t->grabbed_us) return 0;
    if (type == EV_SYN && code == SYN_DROPPED) {
        t->resync = 1;
        return 0;
    }
    if (type == EV_SYN && code == SYN_REPORT) {
        if (t->resync) {
            /* Reads lost: the slots as the kernel has them now, every value given again (the twin's
             * kernel drops those it already has); unreadable, every contact taken as lifted. */
            t->resync = 0;
            if (t->hooks.real_slots(t->hooks.ctx, t->val, &t->slot) != 0)
                for (int slot = 0; slot < TP_SLOTS; slot++) VAL(t, ABS_MT_TRACKING_ID, slot) = -1;
            for (int slot = 0; slot < TP_SLOTS; slot++) t->dirty[slot] = ~0u;
        }
        report(t, &s, trackpad_us, us);
        return s.n;
    }
    /* Only the multi-touch values count: BTN_TOUCH and BTN_TOOL_FINGER come from the contacts kept,
     * and s2716b_ts sends nothing else. */
    if (t->resync || type != EV_ABS || code < TP_MT_FIRST || code >= TP_MT_FIRST + TP_MT_COUNT) return 0;
    if (code == ABS_MT_SLOT) {
        t->slot = value;
    } else if (t->slot >= 0 && t->slot < t->slots) {
        VAL(t, code, t->slot) = value;
        t->dirty[t->slot] |= BIT(code);
    }
    return 0;
}
