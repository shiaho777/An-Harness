package com.anharness.app.phone

/**
 * [T-phone-safety-gate] Final approval gate for agent phone control.
 *
 * Problem: a11y_cli / shizuku_cli expose tap/input/gesture/exec with only
 * the coarse three-level tool permission. Once a user flips a11y_cli to
 * BYPASS, the agent can drive ANY app — including payment apps and the
 * system Settings — with no further check. dsh-Go's phone control keeps a
 * SafetyGate as the FINAL authority: app blocklist, password-field and
 * settings hard blocks, and a session-scoped override the user must
 * explicitly grant.
 *
 * Design: the decision core is a pure function ([decide]) over plain
 * inputs so the whole rule table is JVM-testable (same pattern as
 * ShizukuBackend.decideState). The handler layer feeds it live facts
 * (foreground package, target-node flags) and enforces the verdict.
 * This gate can only TIGHTEN: it never allows what the tool-level
 * OffloadPermissionManager already denied.
 *
 * Verdicts:
 *  - ALLOW: proceed (read-only actions, or non-sensitive mutations).
 *  - ASK:   per-call approval card required; no preset skips it.
 *  - DENY:  hard block with [Reason]; only a session-scoped user override
 *           (granted via an explicit card, cleared on session delete)
 *           downgrades DENY→ASK. Password fields are NEVER overridable.
 */
object PhoneSafetyGate {

    enum class Verdict { ALLOW, ASK, DENY }

    enum class Reason {
        /** Foreground app is on the payment/banking blocklist. */
        PAYMENT_APP,
        /** Foreground app is system Settings (or a known OEM variant). */
        SYSTEM_SETTINGS,
        /** Target node is a password field — never automatable. */
        PASSWORD_FIELD,
        /** Mutation action with no sensitive context: still needs a card. */
        MUTATION,
    }

    /**
     * Payment / banking / wallet packages denied by default. Matched by
     * exact package OR prefix (variants like .lite / regional builds).
     * Deliberately conservative: a false positive costs one override
     * card; a false negative costs real money.
     */
    val paymentPackagePrefixes: List<String> = listOf(
        // CN wallets / banking
        "com.eg.android.AlipayGphone",      // Alipay (+ .lite variants)
        "com.tencent.mm",                   // WeChat (pay inside)
        "com.unionpay",                     // UnionPay / 云闪付
        "com.cmbchina", "cmb.pb",           // CMB / 掌上生活
        "com.icbc", "com.bankcomm",         // ICBC / BoCom
        "com.chinatelecom.bestpay",         // 翼支付
        "com.pingan.pocketbank",            // 平安口袋银行
        "com.cmbc", "com.spdb",             // 民生 / 浦发
        "com.ecitic", "com.cib",            // 中信 / 兴业
        "com.psbc", "com.abchina",          // 邮储 / 农行
        "com.boc", "com.ccb",               // 中行 / 建行
        "com.jd.jrapp",                     // 京东金融
        "com.dianping.v1",                  // 大众点评 (pay flows) — conservative
        "com.sankuai.meituan",              // 美团 (pay flows) — conservative
        // International
        "com.paypal", "com.venmo",
        "com.squareup.cash", "com.coinbase",
        "com.binance", "com.okinc",
        "com.revolut", "com.wise",
        "com.stripe", "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay",         // Samsung Pay
    )

    /** System settings packages (AOSP + common OEM skins). */
    val settingsPackages: Set<String> = setOf(
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.huawei.systemmanager",
        "com.vivo.settings",
        "com.iqoo.secure",
    )

    /** Actions that only observe — never gated beyond tool permission. */
    val readOnlyActions: Set<String> = setOf(
        "ui.dump", "ui.find", "ui.info", "ui.node",
        "extract", "wait", "event", "notify", "dialog.detect", "service",
    )

    /**
     * Pure decision core.
     *
     * @param action canonical action id (e.g. "tap", "input.text", "ui.screenshot")
     * @param foregroundPackage current foreground app package, or null if unknown
     * @param targetIsPassword whether the target node is a password field
     * @param overrideGranted session-scoped user override for blocklisted apps
     */
    fun decide(
        action: String,
        foregroundPackage: String?,
        targetIsPassword: Boolean,
        overrideGranted: Boolean,
    ): Pair<Verdict, Reason?> {
        // Password fields: absolute block, no override path. Automating a
        // password entry is exactly what the agent must never do.
        if (targetIsPassword && action !in readOnlyActions) {
            return Verdict.DENY to Reason.PASSWORD_FIELD
        }
        val pkg = foregroundPackage?.lowercase().orEmpty()
        val isPayment = pkg.isNotEmpty() && paymentPackagePrefixes.any { pkg.startsWith(it.lowercase()) }
        val isSettings = pkg in settingsPackages
        if (isPayment || isSettings) {
            // Reads of a payment screen are also denied — screen content
            // (balances, card numbers) is sensitive. Settings reads are
            // allowed (diagnosing toggle state is a documented flow).
            val sensitive = isPayment || action !in readOnlyActions
            if (sensitive) {
                val reason = if (isPayment) Reason.PAYMENT_APP else Reason.SYSTEM_SETTINGS
                return if (overrideGranted) Verdict.ASK to reason else Verdict.DENY to reason
            }
        }
        // Screenshots can capture whatever is on screen — always a card.
        if (action == "ui.screenshot") return Verdict.ASK to Reason.MUTATION
        if (action in readOnlyActions) return Verdict.ALLOW to null
        // Every remaining action mutates device state → per-call card.
        return Verdict.ASK to Reason.MUTATION
    }

    /** Human-readable block message for the error envelope. */
    fun denyMessage(reason: Reason, foregroundPackage: String?): String = when (reason) {
        Reason.PAYMENT_APP ->
            "Phone control is blocked in payment/banking app" +
                (foregroundPackage?.let { " ($it)" } ?: "") +
                ". This is a hard safety block. If you really need it, grant a " +
                "session-scoped override from the approval card — it expires with the session."
        Reason.SYSTEM_SETTINGS ->
            "Phone control is blocked in system Settings" +
                (foregroundPackage?.let { " ($it)" } ?: "") +
                ". Changing system settings via automation is blocked by default; " +
                "a session-scoped override can be granted from the approval card."
        Reason.PASSWORD_FIELD ->
            "The target is a password field. Automating password entry is never allowed."
        Reason.MUTATION -> "This action requires explicit approval."
    }
}