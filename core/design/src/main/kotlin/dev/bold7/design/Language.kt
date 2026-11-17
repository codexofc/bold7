package dev.bold7.design

import java.util.Locale

/**
 * OS 7's language: the 9900 of reference, its simulator and its captures speak British English
 * ("English(UK)", sim-91a, C), whatever the phone's language, a project decision.
 * Every Bold7 app runs in it (speakOs7Language of core/platform) and SystemUI reads
 * Bold7's resources in it: the strings, the numbers, the day before the month ("13/11", sim-141,
 * C), 24 hours unless the phone's own setting asks for 12.
 */
val OS7_LOCALE: Locale = Locale.UK

/**
 * The names of days and months and AM/PM as the 9900 writes them: "Fri 16 Sep 2011" (gsm 027, C),
 * "PM" (sim-03, C). CLDR's en-GB, which Android and the JVM follow, now writes "Sept" and "pm".
 */
val OS7_NAMES: Locale = Locale.ENGLISH
