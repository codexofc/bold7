/*
 * The LED of BlackBerry OS 7.1 on the Q25, pure: the value of bold7_led in, the LEDs to light and
 * when out. No I/O, tested on the Mac by test_model.c.
 *
 * The 9900 flashes one colour at a time (user guide 7.1, p. 15, M): red for a new message or an
 * incoming call, amber for a low battery, green for the wireless coverage (Screen Display's LED
 * Coverage Indicator), blue for a Bluetooth connection. The Q25's LED is three GPIOs (leds-gpio,
 * no PWM): a colour is a set of them, amber red and green together (D).
 */
#ifndef BOLD7_LED_MODEL_H
#define BOLD7_LED_MODEL_H

#include <stdint.h>

enum { LED_RED = 1, LED_GREEN = 2, LED_BLUE = 4 };

/*
 * The flash, timed from the simulator's controller log (C, docs/BBOS7-VISUEL.md §41):
 * lit 170 ms, every 3.37 s, the same for the red of a call and the green of the coverage.
 */
#define LED_ON_MS 170
#define LED_PERIOD_MS 3370

/* The LEDs a value of bold7_led lights, as `settings get` prints it: "red", "amber", "green", "blue"; anything else none. */
int led_parse(const char *value);

/* [t] ms after the flashing started: whether the LEDs are lit, and in [wait_ms] the ms until that changes. */
int led_lit(int64_t t, int *wait_ms);

#endif
