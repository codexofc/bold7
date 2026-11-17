#include "model.h"

#include <string.h>

int led_parse(const char *value) {
    static const struct {
        const char *name;
        int leds;
    } colours[] = {{"red", LED_RED}, {"amber", LED_RED | LED_GREEN}, {"green", LED_GREEN}, {"blue", LED_BLUE}};
    if (!value) return 0;
    size_t n = strcspn(value, "\n");
    for (size_t i = 0; i < sizeof colours / sizeof colours[0]; i++)
        if (strlen(colours[i].name) == n && strncmp(value, colours[i].name, n) == 0) return colours[i].leds;
    return 0;
}

int led_lit(int64_t t, int *wait_ms) {
    int64_t at = t > 0 ? t % LED_PERIOD_MS : 0;
    if (at < LED_ON_MS) {
        *wait_ms = (int) (LED_ON_MS - at);
        return 1;
    }
    *wait_ms = (int) (LED_PERIOD_MS - at);
    return 0;
}
