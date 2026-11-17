// Import-only declaration (not AOSP code): ILauncherProxy imports ISystemUiProxy, which the
// launcher never calls. SystemUI hands its binder to onInitialize, under its descriptor, and
// QuickstepService only checks that it is there. AGP generates an empty interface from it.
package com.android.systemui.shared.recents;

interface ISystemUiProxy;
