package dev.bold7.sysui

// The bridge into Google Messages'
// main process: SysuiModule opens ExternalMessagingApiService to Messages of Bold7, Google
// Messages keeping the SMS role and its RCS engine. What decides is here, pure.

internal const val MESSAGES = "com.google.android.apps.messaging"
internal const val MESSAGES_API_SERVICE = "$MESSAGES.externalapi.service.ExternalMessagingApiService"
internal const val BRIDGE_TAG = "Bold7/MessagesBridge"

/** The processes this module works in: SystemUI's, Google Messages' main one (the API's host); detached from every other. */
enum class ModuleProcess { SYSTEMUI, MESSAGES, NONE }

fun moduleProcess(name: String): ModuleProcess = when (name) {
    "com.android.systemui" -> ModuleProcess.SYSTEMUI
    // Not :rcs, Jibe's: the service lives in the main process.
    MESSAGES -> ModuleProcess.MESSAGES
    else -> ModuleProcess.NONE
}

/**
 * The bridge goes in on the contract, not on a fingerprint (by design:
 * Messages updates every week or two): its service class is there; Messages of Bold7 checks the
 * rest (GetServiceCapabilities) and says when the door is shut. As SystemUI's hooks, never while
 * a fault is injected nor while the bold7 module is unmounted: safe mode or tools/uninstall.sh
 * turn it off with the rest.
 */
fun bridgeAllowed(serviceClass: Boolean, faultInjected: Boolean, bold7Mounted: Boolean): Boolean =
    serviceClass && !faultInjected && bold7Mounted

/** Prefix of Google Messages' Phenotype flags in shared_prefs/PhenotypePrefs.xml. */
internal const val PHENOTYPE_PREFIX = "bugle_phenotype__"
internal const val BRIDGE_PACKAGE = "dev.bold7.messages"

// The two switches of ExternalMessagingApiService (aqrt, aqrw in 20261027) and its whitelist
// (aqvb), read by their names, which survive Google's reobfuscation where class names do not.
private const val MDE = PHENOTYPE_PREFIX + "enable_andromeda_mde"
private const val ALWAYS_SUPPORTED = PHENOTYPE_PREFIX + "enable_device_always_support_andromeda"
private const val SIGNATURES = PHENOTYPE_PREFIX + "external_api_extra_allowed_signatures"

/**
 * DER certificate of the app signing key in hex, as android.content.pm.Signature(String) reads it:
 * the key that signs dev.bold7.messages (and every Bold7 APK). Public; MessagesBridgeTest checks it against the keystore.
 */
internal const val BRIDGE_SIGNATURE =
    "308204c9308202b1a0030201020208716163d5de7602ff300d06092a864886f70d01010c050030123110300e06035504031307436173636164653020" +
    "170d3236313131313138303234395a180f32313236313031383138303234395a30123110300e060355040313074361736361646530820222300d0609" +
    "2a864886f70d01010105000382020f003082020a02820201009e048b323facbe92a893ff02595ed7bf1b429f65f7a1d5f8825743cb324da591f263f0" +
    "d1b5782464a7bd54523fa3ecf396689147ade9df277f1009af2a97e48d216ee44d907895aebd0db964dfe4ba1d7b708b1972ee904544009db9d2b25e" +
    "db3452a3ecac1d35e3df523009824edf2795d92cd756416351f7bfc725da8b50f700ede259c3f0793b9e7fe81d2e1e463588905868626cb9220bab63" +
    "c19aa0058c50cacb895c5b481c81a27d0d4ec56b6f69a4e293cff95ac8a0f14c3a9e5a2aa92f1e3c330d6751d21a919687ef73bed0e36305f5555c22" +
    "a297e2eabc6aaece42e164954f81f579515965c1a08915b89ab918ce1bfa65b027c8ef1f9d14cc434ef1c7497243f62ae63646753e6a314ef1461125" +
    "0b0eb6cc3591216ff29d5432f6ca4b56cd495c7cac5fe40f45f4bcb3b2da6a533ed4db0672b551c700ba8824637b8a587aac942affcbc097e8523fa4" +
    "eb9c9e633f5c0b23d85737fae5e897a483c10a01f504a4f24e5f364e194a9aae30df3dc6a17b9605cc2ce26b8c908d916a8a501cb873268049af0bee" +
    "e02719f10a0ab5b5e70347db3b839366b09b6a9516c771cce35c94fd48a8b155af37e1831f8c5c8c91626c1f085ade44cf918429798f58f5e90dc347" +
    "a662d4b6aee5017b4f42a8a7988253e04a9c51f8f305013feef2986db140f8c28afa8c01ccdf51e28a4f2031f71b9216c80666e38b0df21fa7020301" +
    "0001a321301f301d0603551d0e041604141b8f9b3c1b943d14cd9032b54dbd4608abfec21c300d06092a864886f70d01010c050003820201007ff33e" +
    "a08b0be81f0dc5f4b7165cbb4ae526be76af0aecbf3fad444838c70c586322361ef8ccd9c4eb3664fab2feb1dd204985416ff3b87abf0c15fdad6094" +
    "57c65590af58a032a68b9d3cf02a1ecba529a77a2532fe3aee2d82b5a97b3f4d1fe19b462d9c1793c8cbf4ed9d4e6bef7db164e91f0d84a6afba1e37" +
    "5ad43baf65a4b7975cca904aba3aef63df3b07362ae13dd3ed2164f81702e110f2b17284567e75b7b91052c22a9eb609e5e6231d65d7dd62241a86b6" +
    "2116f6ec14eec776955126022c26484e36aa06e03d881ad73b03444fa39fb9a97599b690a7724e55d0b0c461b8a217a53a0a3ee54b97d4e1a5b340a3" +
    "269c83b1288a657cbf7f0837050fc12babc6a9dde879d331ec613864e1c82588db71b737f7db5aafefaa7781b7d6d3a6815d516fcc526cf961603a44" +
    "028fed706f4493246a05c1201453566b6c88ec546697bb056759435075b71a6b4103ce6a899ba76623e22fa634abae37a3095905c04afd619c8b6c9e" +
    "1358d8da4f2b57a23357a675212f77ec364090080cd3bd291dc15be841d241fcec2d2770975d0c4f3481d24fa3a4b96a952d8435142197d6e558e6a9" +
    "8955806311c4dffb181e73aa496c9a3157241c9f8e5281ab4c7d6e0330a4e9a3a340df132adbad7f79de75b00903a497fad6781267fb0cd802e7f0e8" +
    "ebcbfe324b1fac4bede2b5224a9484bc8720fb1e3dec4b44763fb9c2e6"

/**
 * What SharedPreferences.getAll() returns to Google Messages, with the API opened: null (the
 * original, unchanged) for every file but PhenotypePrefs, else a copy where both switches are on
 * and the whitelist holds Messages of Bold7's certificate after Google's own entries. Never touches
 * allow_any_app_to_connect_do_not_use_in_public_builds: only our signature gets in.
 */
fun bridgeFlags(prefs: Map<String, *>, signature: String): Map<String, Any?>? {
    if (prefs.keys.none { it.startsWith(PHENOTYPE_PREFIX) }) return null
    val ours = "$BRIDGE_PACKAGE,$signature"
    val google = (prefs[SIGNATURES] as? String).orEmpty()
    val whitelist = when {
        ours in google.split(';') -> google
        google.isEmpty() -> ours
        else -> google.removeSuffix(";") + ";" + ours
    }
    return HashMap<String, Any?>(prefs).apply {
        put(MDE, true)
        put(ALWAYS_SUPPORTED, true)
        put(SIGNATURES, whitelist)
    }
}

// The launcher's Setup screen opens Google Messages' consent page, which is not exported, through
// the bridge: an ordered broadcast to Google Messages, answered with a PendingIntent of Google
// Messages for that page alone.
internal const val OPEN_CONSENT_ACTION = "dev.bold7.action.OPEN_TEXT_CONSENT"
/** Signature, declared by the launcher and held by it alone: the system refuses the broadcast from any other sender. */
internal const val OPEN_CONSENT_PERMISSION = "dev.bold7.permission.OPEN_TEXT_CONSENT"
internal const val CONSENT_PAGE = "$MESSAGES.samsung.settings.CmcUserConsentSettingsActivity"
internal const val CONSENT_PAGE_EXTRA = "page"

/**
 * Whether the bridge answers a request for the consent page from [sender] (the package the
 * broadcast shared, BroadcastReceiver.getSentFromPackage): the launcher only, on top of the
 * permission the system already checked. Null, an identity not shared, is refused.
 */
fun consentPageFor(sender: String?): Boolean = sender == LAUNCHER_PACKAGE
