/*
 * Tests of model.c, built and run on the Mac by ./gradlew test (:system:module:ledTest).
 * Exits non-zero at the first failure.
 */
#include <stdio.h>

#include "model.h"

static int failures;

#define CHECK(cond)                                                    \
    do {                                                               \
        if (!(cond)) {                                                 \
            fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #cond); \
            failures++;                                                \
        }                                                              \
    } while (0)

static void parse(void) {
    CHECK(led_parse("red") == LED_RED);
    CHECK(led_parse("red\n") == LED_RED);
    CHECK(led_parse("amber\n") == (LED_RED | LED_GREEN));
    CHECK(led_parse("green\n") == LED_GREEN);
    CHECK(led_parse("blue\n") == LED_BLUE);
    CHECK(led_parse("off\n") == 0);
    CHECK(led_parse("null\n") == 0);
    CHECK(led_parse("") == 0);
    CHECK(led_parse(NULL) == 0);
    CHECK(led_parse("reds") == 0);
    CHECK(led_parse("re") == 0);
    CHECK(led_parse("RED") == 0);
    CHECK(led_parse(" red") == 0);
}

static int lit(int64_t t, int expected_wait) {
    int wait = -1;
    int on = led_lit(t, &wait);
    if (wait != expected_wait) {
        fprintf(stderr, "led_lit(%lld): wait %d, not %d\n", (long long) t, wait, expected_wait);
        failures++;
    }
    return on;
}

static void blink(void) {
    CHECK(lit(0, LED_ON_MS) == 1);
    CHECK(lit(1, LED_ON_MS - 1) == 1);
    CHECK(lit(LED_ON_MS - 1, 1) == 1);
    CHECK(lit(LED_ON_MS, LED_PERIOD_MS - LED_ON_MS) == 0);
    CHECK(lit(LED_PERIOD_MS - 1, 1) == 0);
    CHECK(lit(LED_PERIOD_MS, LED_ON_MS) == 1);
    CHECK(lit(10 * LED_PERIOD_MS + LED_ON_MS + 5, LED_PERIOD_MS - LED_ON_MS - 5) == 0);
    /* A clock read late (a suspend, a slow wake-up) never gives a wait of 0 nor a negative one. */
    CHECK(lit(-5, LED_ON_MS) == 1);
    CHECK(lit(INT64_C(1) << 40, (int) (LED_PERIOD_MS - (INT64_C(1) << 40) % LED_PERIOD_MS)) == 0);
}

int main(void) {
    parse();
    blink();
    if (failures) {
        fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    printf("led model: all tests passed\n");
    return 0;
}
