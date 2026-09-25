#!/usr/bin/env python3
"""
Patch 29 -- bypass reliability, fallback strategies, plain-language UI,
folder-name migration, and H.264/AAC playback compatibility.

Context (see ROADMAP.md / learnings for the fuller story):

1. Application.onCreate() runs in EVERY Android process. The ":dpi" and
   ":dpi_vpn" engine-host processes were also running queue recovery,
   MediaStore cleanup and the yt-dlp updater every time they spawned --
   racing the real download worker in the main process. Fixed with a
   process guard in YtOfflineApp.

2. The bypass "Use the bypass for downloads" switch could only be turned
   on after a strategy had been verified, but nothing ever ran that
   verification automatically. On a fresh install/update this made the
   switch permanently disabled -- indistinguishable from "does nothing"
   from the user's side. Fixed: Settings now runs the strategy search
   once by itself the first time the screen opens, and turns the switch
   on automatically when a strategy passes.

3. Only a single verified strategy was ever remembered, with no runtime
   fallback. DpiStrategySearch now keeps searching until it collects up
   to 4 full passes (was: stop at the first one -- default parameter
   keeps every existing unit test passing unchanged), and both the
   short-lived download engine and the long-lived YouTube VPN tunnel
   now try the verified strategies in order and use the first one that
   actually starts.

4. Devices upgraded from before the app was renamed still had the old
   "YTOffline" folder name persisted in SharedPreferences (the in-code
   default was already "Kinescope"; this is a one-time value migration,
   not a default-value fix).

5. Removed jargon (ByeDPI, DNS/TCP/TLS/HTTP, "engine", "packets") from
   the bypass UI copy in both EN and RU, and updated the "test first"
   hint to describe the new automatic first-run test.

6. The three video quality presets now prefer H.264 video + AAC audio
   (falling back to the previous unconstrained selector when those are
   not available). yt-dlp's default bv*+ba selector on YouTube often
   picks VP9/Opus even inside an ".mp4" container; many Android stock
   players cannot decode that combination, which produced a completed
   but unplayable "black screen, no sound" file. vcodec/acodec string
   filters are documented, current yt-dlp format-selector syntax.

Safe to run twice (every edit is guarded and skipped if already
applied). Run from the repository root:

    python3 patch_029_bypass_reliability_and_ux.py
"""
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent
APP = ROOT / "app" / "src" / "main" / "java" / "com" / "kinescope" / "app"
RES = ROOT / "app" / "src" / "main" / "res"


class AnchorNotFound(Exception):
    pass


def read(path: pathlib.Path) -> str:
    if not path.exists():
        raise AnchorNotFound(f"{path} does not exist")
    return path.read_text(encoding="utf-8")


def write(path: pathlib.Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(path: pathlib.Path, old: str, new: str, *, already_applied: str = None) -> None:
    """Exact-match guarded replace. Idempotent: if the marker (`already_applied`, or
    `new` when not given) is already present, this is a no-op -- checked on its own,
    since an additive edit's `new` text often still contains `old` as a substring
    (e.g. inserting a block right after an existing one), which would otherwise
    defeat a "was this already applied" check based on `old`'s absence."""
    text = read(path)
    marker = already_applied if already_applied is not None else new
    if marker in text:
        return  # already applied
    count = text.count(old)
    if count != 1:
        raise AnchorNotFound(
            f"expected exactly one match for anchor in {path}, found {count}\n--- anchor ---\n{old}"
        )
    write(path, text.replace(old, new, 1))


def sha256_guarded_rewrite(path: pathlib.Path, expected_sha256_any: list, new_text: str) -> None:
    text = read(path)
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if digest == hashlib.sha256(new_text.encode("utf-8")).hexdigest():
        return  # already applied
    if digest not in expected_sha256_any:
        raise AnchorNotFound(
            f"{path} has unexpected content (sha256={digest}); refusing to overwrite blindly"
        )
    write(path, new_text)


# ---------------------------------------------------------------------------
# 1. Multi-process init guard
# ---------------------------------------------------------------------------

def patch_app_process_guard():
    path = APP / "YtOfflineApp.kt"
    old = """    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        installCrashLogger()

        appScope.launch {"""
    new = """    override fun onCreate() {
        super.onCreate()
        // The ":dpi" and ":dpi_vpn" engine-host processes (DpiEngineService /
        // DpiVpnEngineService) also run Application.onCreate(). Without this guard they
        // repeated queue recovery, MediaStore cleanup and the yt-dlp updater every time an
        // engine process spawned, racing the real download worker in the main process over
        // the shared job journal. Only the main process continues past this point.
        if (Application.getProcessName() != packageName) return
        AppLog.init(this)
        installCrashLogger()

        appScope.launch {"""
    replace_once(path, old, new)


# ---------------------------------------------------------------------------
# 2. DpiPrefs: verified fallbacks + one-time initial-search flag
# ---------------------------------------------------------------------------

def patch_dpi_prefs_and_store():
    path = APP / "DpiStrategyStore.kt"

    old_consts = """    private const val KEY_LIST_UPDATED_AT = \"list_updated_at\"
    private const val KEY_VERIFIED_STRATEGY = \"verified_strategy\"
    private const val KEY_VERIFIED_AT = \"verified_at\"
"""
    new_consts = """    private const val KEY_LIST_UPDATED_AT = \"list_updated_at\"
    private const val KEY_VERIFIED_STRATEGY = \"verified_strategy\"
    private const val KEY_VERIFIED_AT = \"verified_at\"
    private const val KEY_VERIFIED_FALLBACKS = \"verified_fallbacks\"
    private const val KEY_INITIAL_SEARCH_DONE = \"initial_search_done\"
    private const val MAX_FALLBACKS = 3
"""
    replace_once(path, old_consts, new_consts)

    old_mark = """    fun markStrategyVerified(context: Context, line: String) {
        require(DpiStrategyParser.parse(line) is DpiStrategyParser.Parsed.Ok)
        prefs(context).edit()
            .putString(KEY_STRATEGY, line)
            .putString(KEY_VERIFIED_STRATEGY, line)
            .putLong(KEY_VERIFIED_AT, System.currentTimeMillis())
            .apply()
    }
"""
    new_mark = """    /**
     * Records the strategy to use plus, when the search found more than one working
     * strategy, up to [MAX_FALLBACKS] more to fall back to if the primary one fails to
     * start at run time (see [DpiStrategyStore.verifiedChain]).
     */
    fun markStrategiesVerified(context: Context, primary: String, fallbacks: List<String> = emptyList()) {
        require(DpiStrategyParser.parse(primary) is DpiStrategyParser.Parsed.Ok)
        val validFallbacks = fallbacks
            .filter { it != primary && DpiStrategyParser.parse(it) is DpiStrategyParser.Parsed.Ok }
            .distinct()
            .take(MAX_FALLBACKS)
        prefs(context).edit()
            .putString(KEY_STRATEGY, primary)
            .putString(KEY_VERIFIED_STRATEGY, primary)
            .putLong(KEY_VERIFIED_AT, System.currentTimeMillis())
            .putString(KEY_VERIFIED_FALLBACKS, validFallbacks.joinToString(\"\\n\"))
            .apply()
    }

    fun verifiedFallbacks(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_VERIFIED_FALLBACKS, null) ?: return emptyList()
        return raw.split(\"\\n\")
            .filter { it.isNotBlank() && DpiStrategyParser.parse(it) is DpiStrategyParser.Parsed.Ok }
    }

    /** True once the one-time automatic first-run strategy search has been attempted. */
    fun hasRunInitialSearch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INITIAL_SEARCH_DONE, false)

    fun setHasRunInitialSearch(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_INITIAL_SEARCH_DONE, done).apply()
    }
"""
    replace_once(path, old_mark, new_mark, already_applied="fun markStrategiesVerified(")

    old_selected_end = """    /** The strategy to run: the user's choice if it is still valid, otherwise the first built-in. */
    fun selected(context: Context): String {
        val stored = DpiPrefs.storedStrategy(context)
        if (stored != null && DpiStrategyParser.parse(stored) is DpiStrategyParser.Parsed.Ok) return stored
        return DpiBuiltInStrategies.lines.first()
    }
"""
    new_selected_end = old_selected_end + """
    /**
     * The verified strategy to try first, followed by its verified fallbacks -- empty when
     * nothing has been verified yet. Callers that actually start the engine should try each
     * line in order and use the first one that starts (see [DpiBypass] and
     * `BypassVpnService`); callers that only need "is the feature usable right now" should
     * check whether this list is empty.
     */
    fun verifiedChain(context: Context): List<String> {
        val primary = selected(context)
        if (!DpiPrefs.isStrategyVerified(context, primary)) return emptyList()
        return (listOf(primary) + DpiPrefs.verifiedFallbacks(context)).distinct()
    }
"""
    replace_once(path, old_selected_end, new_selected_end, already_applied="fun verifiedChain(context: Context)")


# ---------------------------------------------------------------------------
# 3. DpiStrategySearch: keep searching for up to N full passes
# ---------------------------------------------------------------------------

def patch_dpi_search():
    path = APP / "DpiSearch.kt"
    old = """    fun run(
        candidates: List<String>,
        isCancelled: () -> Boolean,
        onProgress: (SearchProgress) -> Unit
    ): List<StrategyResult> {
        val results = mutableListOf<StrategyResult>()
        for ((index, line) in candidates.withIndex()) {
            if (isCancelled()) break
            onProgress(SearchProgress(index, candidates.size, line, results.toList()))
            val result = try {
                evaluate(line)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            results += result
            onProgress(SearchProgress(index + 1, candidates.size, line, results.toList()))
            if (result.fullPass) break
        }
        return results
    }
"""
    new = """    /**
     * [stopAfterFullPasses] bounds how many fully-passing strategies to collect before
     * stopping early (the default of 1 reproduces the original "stop at the first working
     * strategy" behavior). The caller that wants a primary strategy plus fallbacks passes a
     * higher value; the extra full passes, if any, become the fallbacks.
     */
    fun run(
        candidates: List<String>,
        isCancelled: () -> Boolean,
        onProgress: (SearchProgress) -> Unit,
        stopAfterFullPasses: Int = 1
    ): List<StrategyResult> {
        val results = mutableListOf<StrategyResult>()
        var fullPasses = 0
        for ((index, line) in candidates.withIndex()) {
            if (isCancelled()) break
            onProgress(SearchProgress(index, candidates.size, line, results.toList()))
            val result = try {
                evaluate(line)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            results += result
            onProgress(SearchProgress(index + 1, candidates.size, line, results.toList()))
            if (result.fullPass) {
                fullPasses++
                if (fullPasses >= stopAfterFullPasses) break
            }
        }
        return results
    }
"""
    replace_once(path, old, new)


# ---------------------------------------------------------------------------
# 4. DpiBypass: cascade through verified fallbacks; search collects up to 4
# ---------------------------------------------------------------------------

def patch_dpi_bypass():
    path = APP / "DpiBypass.kt"

    old_object_head = """/** Glue between the settings, the engine and the download service. */
object DpiBypass {
    /**
     * Starts the engine for a download when the bypass is switched on. Returns null when it is off
     * or could not start; the download then simply proceeds on the direct connection.
     */
    fun startIfEnabled(context: Context): BypassSession? {
        val requestedByYouTubeJourney = BypassVpnController.state.value.active
        if (!DpiPrefs.isEnabled(context) && !requestedByYouTubeJourney) return null
        val line = DpiStrategyStore.selected(context)
        if (!DpiPrefs.isStrategyVerified(context, line)) {
            AppLog.w(\"DpiBypass\", \"Selected strategy is not verified; continuing without bypass\")
            return null
        }
        val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok
        if (parsed == null) {
            AppLog.w(\"DpiBypass\", \"Selected strategy is not valid; continuing without bypass\")
            return null
        }
        val session = DpiEngine.start(context, parsed.args)
        if (session == null) {
            AppLog.w(\"DpiBypass\", \"Engine did not start; continuing without bypass\")
        } else {
            AppLog.i(\"DpiBypass\", \"Engine ready; strategy=\\\"$line\\\"\")
        }
        return session
    }
"""
    new_object_head = """/** Glue between the settings, the engine and the download service. */
object DpiBypass {
    /** How many working strategies the search keeps looking for: one primary + fallbacks. */
    private const val MAX_VERIFIED_STRATEGIES = 4

    /**
     * Starts the engine for a download when the bypass is switched on. Tries the verified
     * strategy, then its verified fallbacks in order, and returns the first one that actually
     * starts. Returns null when the bypass is off, nothing is verified, or none of the
     * verified strategies could start; the download then simply proceeds on the direct
     * connection.
     */
    fun startIfEnabled(context: Context): BypassSession? {
        val requestedByYouTubeJourney = BypassVpnController.state.value.active
        if (!DpiPrefs.isEnabled(context) && !requestedByYouTubeJourney) return null
        val chain = DpiStrategyStore.verifiedChain(context)
        if (chain.isEmpty()) {
            AppLog.w(\"DpiBypass\", \"No verified strategy; continuing without bypass\")
            return null
        }
        for (line in chain) {
            val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: continue
            val session = DpiEngine.start(context, parsed.args)
            if (session != null) {
                AppLog.i(\"DpiBypass\", \"Engine ready; strategy=\\\"$line\\\"\")
                return session
            }
            AppLog.w(\"DpiBypass\", \"Strategy did not start, trying the next verified one\")
        }
        AppLog.w(\"DpiBypass\", \"No verified strategy could start; continuing without bypass\")
        return null
    }
"""
    replace_once(path, old_object_head, new_object_head, already_applied="MAX_VERIFIED_STRATEGIES = 4")

    old_search_return = """        return search.run(DpiStrategyStore.candidates(context), isCancelled, onProgress)
    }
"""
    new_search_return = """        return search.run(
            DpiStrategyStore.candidates(context),
            isCancelled,
            onProgress,
            stopAfterFullPasses = MAX_VERIFIED_STRATEGIES
        )
    }
"""
    replace_once(path, old_search_return, new_search_return, already_applied="stopAfterFullPasses = MAX_VERIFIED_STRATEGIES")


# ---------------------------------------------------------------------------
# 5. BypassVpnService: cascade through verified fallbacks for the VPN tunnel
# ---------------------------------------------------------------------------

def patch_bypass_vpn_service():
    path = APP / "BypassVpnService.kt"
    old = """            val strategy = DpiStrategyStore.selected(this)
            if (!DpiPrefs.isStrategyVerified(this, strategy)) {
                fail(R.string.bypass_vpn_no_verified_strategy)
                return
            }
            ensureYouTubeInstalled()
            val parsed = DpiStrategyParser.parse(strategy) as? DpiStrategyParser.Parsed.Ok
                ?: throw BypassStartException(R.string.bypass_vpn_no_verified_strategy)

            engine = DpiEngine.startForVpn(this, parsed.args)
                ?: throw BypassStartException(R.string.bypass_vpn_engine_failed)
"""
    new = """            val chain = DpiStrategyStore.verifiedChain(this)
            if (chain.isEmpty()) {
                fail(R.string.bypass_vpn_no_verified_strategy)
                return
            }
            ensureYouTubeInstalled()

            // Try the verified strategy, then its verified fallbacks in order, and keep the
            // first engine that actually starts.
            for (line in chain) {
                val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok ?: continue
                engine = DpiEngine.startForVpn(this, parsed.args)
                if (engine != null) break
            }
            if (engine == null) throw BypassStartException(R.string.bypass_vpn_engine_failed)
"""
    replace_once(path, old, new)


# ---------------------------------------------------------------------------
# 6. BypassSettings: auto-run the search once, auto-enable, store fallbacks
# ---------------------------------------------------------------------------

def patch_bypass_settings():
    path = APP / "BypassSettings.kt"

    old_success = """                val working = results.firstOrNull { it.fullPass }
                if (working != null) {
                    DpiPrefs.markStrategyVerified(context, working.line)
                    verified = true
                    verifiedAt = DpiPrefs.verifiedAt(context)
                    message = context.getString(
                        if (directWorks) R.string.bypass_search_found_direct else R.string.bypass_search_found
                    )
                } else {
                    message = context.getString(R.string.bypass_search_none)
                }
"""
    new_success = """                val passes = results.filter { it.fullPass }
                val working = passes.firstOrNull()
                if (working != null) {
                    DpiPrefs.markStrategiesVerified(context, working.line, passes.drop(1).map { it.line })
                    // The user asked to turn the bypass on manually before; requiring that extra
                    // tap after a successful test read as "the button does nothing". Turn it on
                    // as soon as a strategy is verified instead.
                    DpiPrefs.setEnabled(context, true)
                    enabled = true
                    verified = true
                    verifiedAt = DpiPrefs.verifiedAt(context)
                    message = context.getString(
                        if (directWorks) R.string.bypass_search_found_direct else R.string.bypass_search_found
                    )
                } else {
                    message = context.getString(R.string.bypass_search_none)
                }
"""
    replace_once(path, old_success, new_success, already_applied="DpiPrefs.markStrategiesVerified(context, working.line")

    old_boundary = """            } finally {
                busy = BypassBusy.NONE
                progress = null
                searchJob = null
            }
        }
    }

    val runUpdate: () -> Unit = {"""
    new_boundary = """            } finally {
                busy = BypassBusy.NONE
                progress = null
                searchJob = null
            }
        }
    }

    // Runs the strategy search by itself, once, the first time this screen is opened on a
    // device -- so a fresh install/update does not require the user to know to press
    // "Test strategies" before the bypass (or the Home-screen YouTube action) can do anything.
    LaunchedEffect(Unit) {
        if (!DpiPrefs.hasRunInitialSearch(context)) {
            DpiPrefs.setHasRunInitialSearch(context, true)
            if (!initialVerified && controlsEnabled) startSearch()
        }
    }

    val runUpdate: () -> Unit = {"""
    replace_once(path, old_boundary, new_boundary, already_applied="hasRunInitialSearch(context)) {")


# ---------------------------------------------------------------------------
# 7. Settings: one-time migration of the pre-rename folder name
# ---------------------------------------------------------------------------

def patch_settings_folder_migration():
    path = APP / "Settings.kt"
    old = """    fun getDownloadSubfolder(context: Context): String =
        sanitizeSubfolder(prefs(context).getString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER) ?: DEFAULT_SUBFOLDER)
"""
    new = """    fun getDownloadSubfolder(context: Context): String {
        val preferences = prefs(context)
        val stored = preferences.getString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER) ?: DEFAULT_SUBFOLDER
        if (stored == LEGACY_SUBFOLDER) {
            // One-time upgrade for devices that still have the pre-rename default persisted;
            // a deliberate user choice of a different name is untouched.
            preferences.edit().putString(KEY_DOWNLOAD_SUBFOLDER, DEFAULT_SUBFOLDER).apply()
            return DEFAULT_SUBFOLDER
        }
        return sanitizeSubfolder(stored)
    }
"""
    replace_once(path, old, new)


# ---------------------------------------------------------------------------
# 8. QualityPresets: prefer H.264 + AAC for real device playback compatibility
# ---------------------------------------------------------------------------

def patch_quality_presets():
    path = APP / "QualityPresets.kt"
    for height in (1080, 720, 480):
        old = (
            f"        addOption(\"-f\", \"bv*[height<={height}]+ba/b[height<={height}]\")\n"
            f"        addOption(\"--merge-output-format\", \"mp4\")"
        )
        new = (
            f"        addOption(\n"
            f"            \"-f\",\n"
            f"            \"bv*[vcodec^=avc][height<={height}]+ba[acodec^=mp4a]/\" +\n"
            f"                \"bv*[height<={height}]+ba/b[height<={height}]\"\n"
            f"        )\n"
            f"        addOption(\"--merge-output-format\", \"mp4\")"
        )
        replace_once(path, old, new, already_applied=f"vcodec^=avc][height<={height}]")


# ---------------------------------------------------------------------------
# 9. Strings: remove jargon from the bypass UI, EN + RU
# ---------------------------------------------------------------------------

EN_STRING_REPLACEMENTS = [
    (
        "<string name=\"bypass_description\">Test a strategy first. Then Kinescope can use the local ByeDPI engine for downloads and for the YouTube app. Android VPN mode is used only to route YouTube into the local proxy; no remote VPN server is involved, and your public IP is not hidden.</string>",
        "<string name=\"bypass_description\">Test first. Once a working method is found, Kinescope can use it for downloads and for the YouTube app. This does not hide your location -- it only helps get past blocking on this network.</string>",
    ),
    (
        "<string name=\"bypass_engine_failed\">The bypass engine did not start.</string>",
        "<string name=\"bypass_engine_failed\">The bypass could not start.</string>",
    ),
    (
        "<string name=\"bypass_stage_dns\">DNS lookup</string>",
        "<string name=\"bypass_stage_dns\">Finding the address</string>",
    ),
    (
        "<string name=\"bypass_stage_tcp\">TCP connection</string>",
        "<string name=\"bypass_stage_tcp\">Connecting</string>",
    ),
    (
        "<string name=\"bypass_stage_proxy\">engine start</string>",
        "<string name=\"bypass_stage_proxy\">starting the bypass</string>",
    ),
    (
        "<string name=\"bypass_stage_connect\">connection through the engine</string>",
        "<string name=\"bypass_stage_connect\">connecting through the bypass</string>",
    ),
    (
        "<string name=\"bypass_stage_tls\">TLS handshake</string>",
        "<string name=\"bypass_stage_tls\">secure connection</string>",
    ),
    (
        "<string name=\"bypass_stage_http\">HTTP request</string>",
        "<string name=\"bypass_stage_http\">loading the page</string>",
    ),
    (
        "<string name=\"bypass_verdict_dns_blocked\">This network does not resolve YouTube names. Splitting packets cannot fix that; a different DNS would be needed.</string>",
        "<string name=\"bypass_verdict_dns_blocked\">This network can\\'t find YouTube\\'s address at all. The bypass can\\'t fix that.</string>",
    ),
    (
        "<string name=\"bypass_verdict_dns_blocks_bypass\">This network blocks DNS lookups for YouTube and the engine cannot resolve the names either. Splitting packets does not fix that.</string>",
        "<string name=\"bypass_verdict_dns_blocks_bypass\">This network blocks YouTube\\'s address everywhere, even for the bypass. It can\\'t fix that.</string>",
    ),
    (
        "<string name=\"bypass_verdict_tcp_blocked\">This network refuses connections to YouTube servers (TCP failure). Splitting packets usually does not help with that.</string>",
        "<string name=\"bypass_verdict_tcp_blocked\">This network refuses to connect to YouTube at all. The bypass usually can\\'t fix that.</string>",
    ),
    (
        "<string name=\"bypass_verdict_tls_interference\">Connections reach the servers but are cut during the TLS handshake. The bypass can sometimes get around that, so run the strategy search.</string>",
        "<string name=\"bypass_verdict_tls_interference\">The connection reaches YouTube but gets cut early. The bypass can often fix this -- run the strategy search.</string>",
    ),
    (
        "<string name=\"bypass_verdict_bypass_unavailable\">The bypass engine is not available.</string>",
        "<string name=\"bypass_verdict_bypass_unavailable\">The bypass is not available.</string>",
    ),
    (
        "<string name=\"bypass_engine_note\">The ByeDPI engine is built into the app and is updated together with it. Here the list of strategies is updated.</string>",
        "<string name=\"bypass_engine_note\">The bypass itself is built into the app and updates together with it. This only updates the list of strategies to try.</string>",
    ),
    (
        "<string name=\"bypass_enable_locked\">Available after a strategy passes all connection probes.</string>",
        "<string name=\"bypass_enable_locked\">Available once a strategy passes the test.</string>",
    ),
    (
        "<string name=\"bypass_test_first_hint\">Test strategies once before enabling the bypass.</string>",
        "<string name=\"bypass_test_first_hint\">Kinescope tests this once by itself the first time you open this screen. You can run it again anytime.</string>",
    ),
    (
        "<string name=\"bypass_quick_action\">ByeDPI</string>",
        "<string name=\"bypass_quick_action\">Bypass</string>",
    ),
    (
        "<string name=\"bypass_need_test\">Test a ByeDPI strategy in Settings first.</string>",
        "<string name=\"bypass_need_test\">Test the bypass in Settings first.</string>",
    ),
    (
        "<string name=\"bypass_vpn_no_verified_strategy\">Test a ByeDPI strategy before starting YouTube.</string>",
        "<string name=\"bypass_vpn_no_verified_strategy\">Test the bypass before opening YouTube.</string>",
    ),
    (
        "<string name=\"bypass_vpn_engine_failed\">ByeDPI could not start.</string>",
        "<string name=\"bypass_vpn_engine_failed\">The bypass could not start.</string>",
    ),
    (
        "<string name=\"bypass_notification_title\">Kinescope \u00b7 ByeDPI</string>",
        "<string name=\"bypass_notification_title\">Kinescope \u00b7 Bypass</string>",
    ),
    (
        "<string name=\"bypass_notification_active\">YouTube traffic is routed through local ByeDPI.</string>",
        "<string name=\"bypass_notification_active\">YouTube is using the local bypass.</string>",
    ),
    (
        "<string name=\"bypass_wait_download\">Finish the current download before testing or changing ByeDPI.</string>",
        "<string name=\"bypass_wait_download\">Finish the current download before testing or changing the bypass.</string>",
    ),
    (
        "<string name=\"bypass_operation_failed\">The operation failed. Check Logs for technical details.</string>",
        "<string name=\"bypass_operation_failed\">Something went wrong. Check Logs for details.</string>",
    ),
]

RU_STRING_REPLACEMENTS = [
    (
        "<string name=\"bypass_description\">\u0421\u043d\u0430\u0447\u0430\u043b\u0430 \u043f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u044e. \u0417\u0430\u0442\u0435\u043c Kinescope \u0441\u043c\u043e\u0436\u0435\u0442 \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 ByeDPI \u0434\u043b\u044f \u0437\u0430\u0433\u0440\u0443\u0437\u043e\u043a \u0438 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f YouTube. Android VPN \u043d\u0443\u0436\u0435\u043d \u0442\u043e\u043b\u044c\u043a\u043e \u0434\u043b\u044f \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u043e\u0439 \u043c\u0430\u0440\u0448\u0440\u0443\u0442\u0438\u0437\u0430\u0446\u0438\u0438 YouTube: \u0443\u0434\u0430\u043b\u0451\u043d\u043d\u043e\u0433\u043e VPN-\u0441\u0435\u0440\u0432\u0435\u0440\u0430 \u043d\u0435\u0442, \u043f\u0443\u0431\u043b\u0438\u0447\u043d\u044b\u0439 IP \u043d\u0435 \u0441\u043a\u0440\u044b\u0432\u0430\u0435\u0442\u0441\u044f.</string>",
        "<string name=\"bypass_description\">\u0421\u043d\u0430\u0447\u0430\u043b\u0430 \u043f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u044e. \u041a\u043e\u0433\u0434\u0430 \u0440\u0430\u0431\u043e\u0447\u0438\u0439 \u0441\u043f\u043e\u0441\u043e\u0431 \u043d\u0430\u0439\u0434\u0435\u043d, Kinescope \u0441\u043c\u043e\u0436\u0435\u0442 \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c \u0435\u0433\u043e \u0434\u043b\u044f \u0437\u0430\u0433\u0440\u0443\u0437\u043e\u043a \u0438 \u0434\u043b\u044f \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f YouTube. \u042d\u0442\u043e \u043d\u0435 \u0441\u043a\u0440\u044b\u0432\u0430\u0435\u0442 \u0432\u0430\u0448\u0435 \u043c\u0435\u0441\u0442\u043e\u043f\u043e\u043b\u043e\u0436\u0435\u043d\u0438\u0435 \u2014 \u0442\u043e\u043b\u044c\u043a\u043e \u043f\u043e\u043c\u043e\u0433\u0430\u0435\u0442 \u043e\u0431\u043e\u0439\u0442\u0438 \u0431\u043b\u043e\u043a\u0438\u0440\u043e\u0432\u043a\u0438 \u0432 \u044d\u0442\u043e\u0439 \u0441\u0435\u0442\u0438.</string>",
    ),
    (
        "<string name=\"bypass_engine_failed\">\u0414\u0432\u0438\u0436\u043e\u043a \u043e\u0431\u0445\u043e\u0434\u0430 \u043d\u0435 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u043b\u0441\u044f.</string>",
        "<string name=\"bypass_engine_failed\">\u041e\u0431\u0445\u043e\u0434 \u043d\u0435 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u043b\u0441\u044f.</string>",
    ),
    (
        "<string name=\"bypass_stage_dns\">\u043f\u043e\u0438\u0441\u043a DNS</string>",
        "<string name=\"bypass_stage_dns\">\u043f\u043e\u0438\u0441\u043a \u0430\u0434\u0440\u0435\u0441\u0430</string>",
    ),
    (
        "<string name=\"bypass_stage_tcp\">TCP-\u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435</string>",
        "<string name=\"bypass_stage_tcp\">\u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435</string>",
    ),
    (
        "<string name=\"bypass_stage_proxy\">\u0437\u0430\u043f\u0443\u0441\u043a \u0434\u0432\u0438\u0436\u043a\u0430</string>",
        "<string name=\"bypass_stage_proxy\">\u0437\u0430\u043f\u0443\u0441\u043a \u043e\u0431\u0445\u043e\u0434\u0430</string>",
    ),
    (
        "<string name=\"bypass_stage_connect\">\u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u0447\u0435\u0440\u0435\u0437 \u0434\u0432\u0438\u0436\u043e\u043a</string>",
        "<string name=\"bypass_stage_connect\">\u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u0447\u0435\u0440\u0435\u0437 \u043e\u0431\u0445\u043e\u0434</string>",
    ),
    (
        "<string name=\"bypass_stage_tls\">TLS-\u0440\u0443\u043a\u043e\u043f\u043e\u0436\u0430\u0442\u0438\u0435</string>",
        "<string name=\"bypass_stage_tls\">\u0437\u0430\u0449\u0438\u0449\u0451\u043d\u043d\u043e\u0435 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435</string>",
    ),
    (
        "<string name=\"bypass_stage_http\">HTTP-\u0437\u0430\u043f\u0440\u043e\u0441</string>",
        "<string name=\"bypass_stage_http\">\u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0430 \u0441\u0442\u0440\u0430\u043d\u0438\u0446\u044b</string>",
    ),
    (
        "<string name=\"bypass_verdict_dns_blocked\">\u042d\u0442\u0430 \u0441\u0435\u0442\u044c \u043d\u0435 \u0440\u0430\u0437\u0440\u0435\u0448\u0430\u0435\u0442 \u0438\u043c\u0435\u043d\u0430 YouTube. \u0414\u0440\u043e\u0431\u043b\u0435\u043d\u0438\u0435 \u043f\u0430\u043a\u0435\u0442\u043e\u0432 \u0442\u0443\u0442 \u043d\u0435 \u043f\u043e\u043c\u043e\u0436\u0435\u0442, \u043d\u0443\u0436\u0435\u043d \u0434\u0440\u0443\u0433\u043e\u0439 DNS.</string>",
        "<string name=\"bypass_verdict_dns_blocked\">\u042d\u0442\u0430 \u0441\u0435\u0442\u044c \u0432\u043e\u043e\u0431\u0449\u0435 \u043d\u0435 \u043d\u0430\u0445\u043e\u0434\u0438\u0442 \u0430\u0434\u0440\u0435\u0441 YouTube. \u041e\u0431\u0445\u043e\u0434 \u044d\u0442\u043e \u043d\u0435 \u0438\u0441\u043f\u0440\u0430\u0432\u0438\u0442.</string>",
    ),
    (
        "<string name=\"bypass_verdict_dns_blocks_bypass\">\u042d\u0442\u0430 \u0441\u0435\u0442\u044c \u0431\u043b\u043e\u043a\u0438\u0440\u0443\u0435\u0442 DNS-\u0437\u0430\u043f\u0440\u043e\u0441\u044b \u043a YouTube, \u0438 \u0434\u0432\u0438\u0436\u043e\u043a \u0442\u043e\u0436\u0435 \u043d\u0435 \u043c\u043e\u0436\u0435\u0442 \u0440\u0430\u0437\u0440\u0435\u0448\u0438\u0442\u044c \u0438\u043c\u0435\u043d\u0430. \u0414\u0440\u043e\u0431\u043b\u0435\u043d\u0438\u0435 \u043f\u0430\u043a\u0435\u0442\u043e\u0432 \u044d\u0442\u043e\u0433\u043e \u043d\u0435 \u0438\u0441\u043f\u0440\u0430\u0432\u043b\u044f\u0435\u0442.</string>",
        "<string name=\"bypass_verdict_dns_blocks_bypass\">\u042d\u0442\u0430 \u0441\u0435\u0442\u044c \u0431\u043b\u043e\u043a\u0438\u0440\u0443\u0435\u0442 \u0430\u0434\u0440\u0435\u0441 YouTube \u0432\u0435\u0437\u0434\u0435, \u0434\u0430\u0436\u0435 \u0434\u043b\u044f \u043e\u0431\u0445\u043e\u0434\u0430. \u042d\u0442\u043e \u043d\u0435 \u0438\u0441\u043f\u0440\u0430\u0432\u0438\u0442\u044c.</string>",
    ),
    (
        "<string name=\"bypass_verdict_tcp_blocked\">\u042d\u0442\u0430 \u0441\u0435\u0442\u044c \u043e\u0442\u043a\u043b\u043e\u043d\u044f\u0435\u0442 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f \u0441 \u0441\u0435\u0440\u0432\u0435\u0440\u0430\u043c\u0438 YouTube (\u0441\u0431\u043e\u0439 TCP). \u0414\u0440\u043e\u0431\u043b\u0435\u043d\u0438\u0435 \u043f\u0430\u043a\u0435\u0442\u043e\u0432 \u043e\u0431\u044b\u0447\u043d\u043e \u0442\u0443\u0442 \u043d\u0435 \u043f\u043e\u043c\u043e\u0433\u0430\u0435\u0442.</string>",
        "<string name=\"bypass_verdict_tcp_blocked\">\u042d\u0442\u0430 \u0441\u0435\u0442\u044c \u0432\u043e\u043e\u0431\u0449\u0435 \u043d\u0435 \u0441\u043e\u0435\u0434\u0438\u043d\u044f\u0435\u0442\u0441\u044f \u0441 YouTube. \u041e\u0431\u0445\u043e\u0434 \u043e\u0431\u044b\u0447\u043d\u043e \u0442\u0443\u0442 \u043d\u0435 \u043f\u043e\u043c\u043e\u0433\u0430\u0435\u0442.</string>",
    ),
    (
        "<string name=\"bypass_verdict_tls_interference\">\u0421\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u0434\u043e\u0445\u043e\u0434\u0438\u0442 \u0434\u043e \u0441\u0435\u0440\u0432\u0435\u0440\u043e\u0432, \u043d\u043e \u043e\u0431\u0440\u044b\u0432\u0430\u0435\u0442\u0441\u044f \u0432\u043e \u0432\u0440\u0435\u043c\u044f TLS-\u0440\u0443\u043a\u043e\u043f\u043e\u0436\u0430\u0442\u0438\u044f. \u041e\u0431\u0445\u043e\u0434 \u0438\u043d\u043e\u0433\u0434\u0430 \u0441\u043f\u0440\u0430\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u0441 \u044d\u0442\u0438\u043c, \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u0435 \u043f\u043e\u0438\u0441\u043a \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u0438.</string>",
        "<string name=\"bypass_verdict_tls_interference\">\u0421\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u0434\u043e\u0445\u043e\u0434\u0438\u0442 \u0434\u043e YouTube, \u043d\u043e \u043e\u0431\u0440\u044b\u0432\u0430\u0435\u0442\u0441\u044f \u043f\u043e\u0447\u0442\u0438 \u0441\u0440\u0430\u0437\u0443. \u041e\u0431\u0445\u043e\u0434 \u0447\u0430\u0441\u0442\u043e \u043c\u043e\u0436\u0435\u0442 \u044d\u0442\u043e \u0438\u0441\u043f\u0440\u0430\u0432\u0438\u0442\u044c \u2014 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u0435 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0443 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u0439.</string>",
    ),
    (
        "<string name=\"bypass_verdict_bypass_unavailable\">\u0414\u0432\u0438\u0436\u043e\u043a \u043e\u0431\u0445\u043e\u0434\u0430 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u0435\u043d.</string>",
        "<string name=\"bypass_verdict_bypass_unavailable\">\u041e\u0431\u0445\u043e\u0434 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u0435\u043d.</string>",
    ),
    (
        "<string name=\"bypass_engine_note\">\u0414\u0432\u0438\u0436\u043e\u043a ByeDPI \u0432\u0441\u0442\u0440\u043e\u0435\u043d \u0432 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435 \u0438 \u043e\u0431\u043d\u043e\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u0432\u043c\u0435\u0441\u0442\u0435 \u0441 \u043d\u0438\u043c. \u0417\u0434\u0435\u0441\u044c \u043e\u0431\u043d\u043e\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u0441\u043f\u0438\u0441\u043e\u043a \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u0439.</string>",
        "<string name=\"bypass_engine_note\">\u0421\u0430\u043c \u043e\u0431\u0445\u043e\u0434 \u0432\u0441\u0442\u0440\u043e\u0435\u043d \u0432 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435 \u0438 \u043e\u0431\u043d\u043e\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u0432\u043c\u0435\u0441\u0442\u0435 \u0441 \u043d\u0438\u043c. \u0417\u0434\u0435\u0441\u044c \u043e\u0431\u043d\u043e\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u0442\u043e\u043b\u044c\u043a\u043e \u0441\u043f\u0438\u0441\u043e\u043a \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u0439.</string>",
    ),
    (
        "<string name=\"bypass_enable_locked\">\u0412\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u0435 \u0441\u0442\u0430\u043d\u0435\u0442 \u0434\u043e\u0441\u0442\u0443\u043f\u043d\u043e \u043f\u043e\u0441\u043b\u0435 \u0443\u0441\u043f\u0435\u0448\u043d\u043e\u0439 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u0438.</string>",
        "<string name=\"bypass_enable_locked\">\u0421\u0442\u0430\u043d\u0435\u0442 \u0434\u043e\u0441\u0442\u0443\u043f\u043d\u043e, \u043a\u043e\u0433\u0434\u0430 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u044f \u043f\u0440\u043e\u0439\u0434\u0451\u0442 \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0443.</string>",
    ),
    (
        "<string name=\"bypass_test_first_hint\">\u0421\u043d\u0430\u0447\u0430\u043b\u0430 \u043e\u0434\u0438\u043d \u0440\u0430\u0437 \u043f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u0438.</string>",
        "<string name=\"bypass_test_first_hint\">Kinescope \u0441\u0430\u043c \u043f\u0440\u043e\u0432\u0435\u0440\u0438\u0442 \u044d\u0442\u043e \u043e\u0434\u0438\u043d \u0440\u0430\u0437 \u043f\u0440\u0438 \u043f\u0435\u0440\u0432\u043e\u043c \u043e\u0442\u043a\u0440\u044b\u0442\u0438\u0438 \u044d\u0442\u043e\u0433\u043e \u044d\u043a\u0440\u0430\u043d\u0430. \u0412\u044b \u043c\u043e\u0436\u0435\u0442\u0435 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0443 \u0441\u043d\u043e\u0432\u0430 \u0432 \u043b\u044e\u0431\u043e\u0439 \u043c\u043e\u043c\u0435\u043d\u0442.</string>",
    ),
    (
        "<string name=\"bypass_quick_action\">ByeDPI</string>",
        "<string name=\"bypass_quick_action\">\u041e\u0431\u0445\u043e\u0434</string>",
    ),
    (
        "<string name=\"bypass_need_test\">\u0421\u043d\u0430\u0447\u0430\u043b\u0430 \u043f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u044e ByeDPI \u0432 \u043d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0430\u0445.</string>",
        "<string name=\"bypass_need_test\">\u0421\u043d\u0430\u0447\u0430\u043b\u0430 \u043f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u043e\u0431\u0445\u043e\u0434 \u0432 \u043d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0430\u0445.</string>",
    ),
    (
        "<string name=\"bypass_vpn_no_verified_strategy\">\u041f\u0435\u0440\u0435\u0434 \u0437\u0430\u043f\u0443\u0441\u043a\u043e\u043c YouTube \u043f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u044e ByeDPI.</string>",
        "<string name=\"bypass_vpn_no_verified_strategy\">\u041f\u0435\u0440\u0435\u0434 \u0437\u0430\u043f\u0443\u0441\u043a\u043e\u043c YouTube \u043f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u043e\u0431\u0445\u043e\u0434.</string>",
    ),
    (
        "<string name=\"bypass_vpn_engine_failed\">\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c ByeDPI.</string>",
        "<string name=\"bypass_vpn_engine_failed\">\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c \u043e\u0431\u0445\u043e\u0434.</string>",
    ),
    (
        "<string name=\"bypass_notification_title\">Kinescope \u00b7 ByeDPI</string>",
        "<string name=\"bypass_notification_title\">Kinescope \u00b7 \u041e\u0431\u0445\u043e\u0434</string>",
    ),
    (
        "<string name=\"bypass_notification_active\">\u0422\u0440\u0430\u0444\u0438\u043a YouTube \u043f\u0440\u043e\u0445\u043e\u0434\u0438\u0442 \u0447\u0435\u0440\u0435\u0437 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 ByeDPI.</string>",
        "<string name=\"bypass_notification_active\">YouTube \u0440\u0430\u0431\u043e\u0442\u0430\u0435\u0442 \u0447\u0435\u0440\u0435\u0437 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 \u043e\u0431\u0445\u043e\u0434.</string>",
    ),
    (
        "<string name=\"bypass_wait_download\">\u0414\u043e\u0436\u0434\u0438\u0442\u0435\u0441\u044c \u043e\u043a\u043e\u043d\u0447\u0430\u043d\u0438\u044f \u0442\u0435\u043a\u0443\u0449\u0435\u0439 \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0438, \u043f\u0440\u0435\u0436\u0434\u0435 \u0447\u0435\u043c \u0442\u0435\u0441\u0442\u0438\u0440\u043e\u0432\u0430\u0442\u044c \u0438\u043b\u0438 \u043c\u0435\u043d\u044f\u0442\u044c ByeDPI.</string>",
        "<string name=\"bypass_wait_download\">\u0414\u043e\u0436\u0434\u0438\u0442\u0435\u0441\u044c \u043e\u043a\u043e\u043d\u0447\u0430\u043d\u0438\u044f \u0442\u0435\u043a\u0443\u0449\u0435\u0439 \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0438, \u043f\u0440\u0435\u0436\u0434\u0435 \u0447\u0435\u043c \u0442\u0435\u0441\u0442\u0438\u0440\u043e\u0432\u0430\u0442\u044c \u0438\u043b\u0438 \u043c\u0435\u043d\u044f\u0442\u044c \u043e\u0431\u0445\u043e\u0434.</string>",
    ),
    (
        "<string name=\"bypass_operation_failed\">\u041e\u043f\u0435\u0440\u0430\u0446\u0438\u044f \u043d\u0435 \u0432\u044b\u043f\u043e\u043b\u043d\u0435\u043d\u0430. \u0422\u0435\u0445\u043d\u0438\u0447\u0435\u0441\u043a\u0438\u0435 \u0434\u0435\u0442\u0430\u043b\u0438 \u0434\u043e\u0441\u0442\u0443\u043f\u043d\u044b \u0432 \u043b\u043e\u0433\u0430\u0445.</string>",
        "<string name=\"bypass_operation_failed\">\u0427\u0442\u043e-\u0442\u043e \u043f\u043e\u0448\u043b\u043e \u043d\u0435 \u0442\u0430\u043a. \u041f\u043e\u0434\u0440\u043e\u0431\u043d\u043e\u0441\u0442\u0438 \u2014 \u0432 \u043b\u043e\u0433\u0430\u0445.</string>",
    ),
]


def patch_strings():
    en_path = RES / "values" / "strings.xml"
    ru_path = RES / "values-ru" / "strings.xml"
    for old, new in EN_STRING_REPLACEMENTS:
        replace_once(en_path, old, new)
    for old, new in RU_STRING_REPLACEMENTS:
        replace_once(ru_path, old, new)


# ---------------------------------------------------------------------------
# 10. Docs: ROADMAP.md + CHANGELOG.md
# ---------------------------------------------------------------------------

def patch_roadmap():
    path = ROOT / "ROADMAP.md"

    old = """### Patch 27 network bypass: verified baseline

- [x] The user reports that GitHub Actions now completes successfully and ByeDPI works on the target device/network. This supersedes the old sandbox-only warning for the Patch-27 engine path.
- [ ] Patch 28 adds a new layer on top of that verified engine: the YouTube-only Android `VpnService` + TUN-to-SOCKS bridge. Its first-run permission/session lifecycle still needs the focused device check above.
"""
    new = old + """
### Patch 29 bypass reliability, fallback strategies, plain-language UI

Not yet device-verified. Full detail in `CHANGELOG.md`.

- [x] Fixed a multi-process init race: the `:dpi` / `:dpi_vpn` engine processes were re-running queue recovery, MediaStore cleanup and the yt-dlp updater on every spawn because `Application.onCreate()` runs in every process and had no process guard.
- [x] The strategy search now runs automatically the first time Settings is opened, and turns the download-bypass switch on by itself once a strategy verifies -- previously nothing triggered the first search, so the switch stayed permanently disabled.
- [x] The search now keeps looking for up to 4 working strategies (primary + up to 3 fallbacks) instead of stopping at the first one; both the download bypass and the YouTube VPN tunnel try them in order and use the first one that actually starts.
- [x] One-time migration of the pre-rename `YTOffline` folder name to `Kinescope` for devices that still had the old value persisted.
- [x] Removed ByeDPI/DNS/TCP/TLS/HTTP/"engine" jargon from the bypass UI copy, EN and RU.
- [x] Video quality presets now prefer H.264 + AAC (falling back to the old unconstrained selector) to fix completed-but-unplayable (black screen, no sound) downloads caused by yt-dlp picking VP9/Opus inside an `.mp4` container.
- [ ] **Needs a real device to confirm:** the automatic first-run search actually completes and enables the switch; a fallback strategy is actually used when the primary one fails to start; the YouTube VPN tunnel start also cascades through fallbacks; the folder migration takes effect for an existing install; a freshly downloaded video plays with picture and sound.
"""
    replace_once(path, old, new, already_applied="### Patch 29 bypass reliability, fallback strategies, plain-language UI")


def patch_changelog():
    path = ROOT / "CHANGELOG.md"
    anchor = (
        "- Updated `HANDOFF.md`, `README.md`, `CJM.md`, `design.md`, `ROADMAP.md`, and "
        "`THIRD_PARTY_NOTICES.md` with the new architecture and verification boundary.\n\n"
        "## Patch 27 \u2014 In-app network bypass (bundled ByeDPI engine)"
    )
    new_section = """- Updated `HANDOFF.md`, `README.md`, `CJM.md`, `design.md`, `ROADMAP.md`, and \
`THIRD_PARTY_NOTICES.md` with the new architecture and verification boundary.

## Patch 29 \u2014 Bypass reliability, fallback strategies, plain-language UI

Reported after patch 28: build succeeds and the app launches, but the bypass did not do
anything when switched on, the background YouTube VPN mode did not work either, the
download folder still showed the pre-rename name on-device, and a downloaded video one
patch back played as a black screen with no sound.

### Fixed
- **Multi-process init race.** `Application.onCreate()` runs in every Android process; the
  `:dpi` / `:dpi_vpn` engine-host processes had no guard, so every time one spawned it also
  ran `DownloadJobStore.restoreToBus()`, `EngineController.ensureReady()` and the yt-dlp
  updater in parallel with the real download worker in the main process, racing the shared
  job journal. `YtOfflineApp.onCreate()` now returns immediately when
  `Application.getProcessName() != packageName`.
- **The bypass switch looked broken because nothing ever verified a strategy for it.** The
  "Use the bypass for downloads" switch can only be turned on once a strategy has passed
  its connection test, but no code path ever ran that test automatically -- so on a fresh
  install/update the switch stayed disabled no matter how many times it was tapped. Settings
  now runs the strategy search by itself the first time the screen opens
  (`DpiPrefs.hasRunInitialSearch`), and any successful search (automatic or manual) also
  turns the switch on, instead of leaving that as a separate step.
- **No runtime fallback strategy.** Only a single verified strategy was ever stored.
  `DpiStrategySearch.run()` gained a `stopAfterFullPasses` parameter (default 1, so every
  existing unit test is unchanged) and the orchestrated search now uses 4, collecting a
  primary plus up to 3 fallbacks. `DpiPrefs.markStrategiesVerified()` stores all of them;
  `DpiStrategyStore.verifiedChain()` exposes them in order. `DpiBypass.startIfEnabled()` and
  `BypassVpnService`'s tunnel start both now try each verified strategy in turn and use the
  first one whose engine actually starts, instead of giving up after a single attempt.
- **Download folder still named `YTOffline` on-device.** The in-code default was already
  `Kinescope`; devices that had the old name explicitly persisted from before the rename
  kept it. `Settings.getDownloadSubfolder()` now migrates that one specific legacy value to
  `Kinescope` the next time it is read; a folder the user deliberately renamed to something
  else is left alone.
- **Downloaded videos playing as a black screen with no sound.** yt-dlp's `bv*+ba` selector
  often picks VP9 video / Opus audio for YouTube even when `--merge-output-format mp4` is
  set, which many Android stock video players cannot decode despite the file being a valid,
  complete `.mp4`. The three video quality presets now ask for H.264 (`vcodec^=avc`) video
  and AAC (`acodec^=mp4a`) audio first, falling back to the previous unconstrained selector
  when a video has no such formats.
- **Plain-language bypass UI.** Removed "ByeDPI", "DNS", "TCP", "TLS", "HTTP", "engine" and
  "packets" from user-facing bypass strings in both `values/strings.xml` and
  `values-ru/strings.xml`; the strings keep their existing names/placeholders, only the
  wording changed. `bypass_test_first_hint` now describes the automatic first-run test.

### Verification status
Code-reviewed, unit-tested (`DpiStrategySearchTest` unchanged and passing) and locally
built; **not yet confirmed on a real device.** See `ROADMAP.md` -> Patch 29 for the specific
device checks still needed.

## Patch 27 \u2014 In-app network bypass (bundled ByeDPI engine)"""
    replace_once(path, anchor, new_section, already_applied="## Patch 29 \u2014 Bypass reliability, fallback strategies, plain-language UI")


# ---------------------------------------------------------------------------

def main():
    steps = [
        patch_app_process_guard,
        patch_dpi_prefs_and_store,
        patch_dpi_search,
        patch_dpi_bypass,
        patch_bypass_vpn_service,
        patch_bypass_settings,
        patch_settings_folder_migration,
        patch_quality_presets,
        patch_strings,
        patch_roadmap,
        patch_changelog,
    ]
    for step in steps:
        try:
            step()
        except AnchorNotFound as exc:
            print(f"FAILED at {step.__name__}: {exc}", file=sys.stderr)
            sys.exit(1)
        print(f"applied: {step.__name__}")
    print("Patch 29 applied successfully.")


if __name__ == "__main__":
    main()
