package com.feldman.scholix.lemida

import org.json.JSONTokener

/** One pending browser sequence spans picker inspection, code preparation and submission. */
internal class LemidaMfaPoll(
    private val mfa: LemidaMfaState,
    private val now: () -> Long,
    private val alive: () -> Boolean,
    private val url: () -> String?,
    private val prepareReception: () -> Boolean,
    private val evaluate: (String, (String?) -> Unit) -> Unit,
    private val scheduleTimeout: (() -> Unit) -> Unit,
    private val status: (String) -> Unit,
    private val requestSms: () -> Boolean = { true },
) {
    private val probe = LemidaLoginProbe()
    fun invalidate() = probe.invalidate()

    fun poll() {
        if (!alive()) return
        val request = probe.start() ?: return
        val document = url()
        fun current() = alive() && probe.isCurrent(request) && url() == document
        fun finish() { probe.complete(request) }
        scheduleTimeout { probe.abandon(request) }
        evaluate(LemidaSms.selectScript(mfa.alternativeClicked, mfa.smsSelected)) selected@{ raw ->
            if (!current()) return@selected
            when (runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()) {
                "alternative" -> {
                    if (!mfa.prepareAlternative()) { finish(); return@selected }
                    evaluate(LemidaSms.chooseScript("alternative")) {
                        if (current()) finish()
                    }
                }
                "sms" -> {
                    if (!prepareReception() || mfa.smsSelected || !requestSms() || !mfa.prepareSms(now())) { finish(); return@selected }
                    status("SMS requested. Waiting for the Microsoft verification code…")
                    evaluate(LemidaSms.chooseScript("sms")) {
                        if (current()) {
                            if (it == "false") status("The verification page changed. Select SMS in the browser to continue.")
                            finish()
                        }
                    }
                }
                "otp", "otp-waiting" -> {
                    mfa.observeOtp(now())
                    prepareReception()
                    val code = mfa.pendingCode(now())
                    if (code == null) { finish(); return@selected }
                    evaluate(LemidaSms.prepareCodeScript(code)) ready@{ ready ->
                        if (!current()) return@ready
                        if (ready != "true" || mfa.pendingCode(now()) != code) { finish(); return@ready }
                        val toSubmit = mfa.consumeCode(now())
                        if (toSubmit == null) { finish(); return@ready }
                        evaluate(LemidaSms.submitScript(toSubmit)) {
                            if (current()) {
                                if (it == "true") status("Microsoft SMS code submitted. Completing sign-in…")
                                else if (it == "false") status("The verification page changed. Enter the code in the browser to continue.")
                                finish()
                            }
                        }
                    }
                }
                else -> finish()
            }
        }
    }
}
