package com.feldman.scholix.lemida

import org.json.JSONTokener

/** Navigation releases pending callbacks, but never permits a second password attempt. */
internal class LemidaCredentialPoll(
    private val credentials: () -> Pair<String, String>?,
    private val alive: () -> Boolean,
    private val url: () -> String?,
    private val evaluate: (String, (String?) -> Unit) -> Unit,
    private val scheduleTimeout: (() -> Unit) -> Unit,
    private val missing: () -> Unit,
    private val status: (String) -> Unit,
    private val continueMfa: () -> Unit,
    private val preferredAccount: () -> String? = { credentials()?.first },
    private val rememberAccount: (String) -> Unit = {},
    private val requiresInteraction: () -> Unit = {},
) {
    private val probe = LemidaLoginProbe()
    private val attempted = mutableSetOf<String>()
    fun invalidate() = probe.invalidate()
    // Only an explicit edit of sign-in details permits trying new credentials.
    fun detailsChanged() { invalidate(); attempted.clear() }

    fun poll() {
        if (!alive()) return
        val request = probe.start() ?: return
        val document = url()
        val details = credentials()
        val account = preferredAccount()
        fun current() = alive() && probe.isCurrent(request) && url() == document && credentials() == details && preferredAccount() == account
        scheduleTimeout { probe.abandon(request) }
        evaluate(LemidaCredentialScript.probe(account)) selected@{ raw ->
            if (!current()) return@selected
            val action = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            when (action) {
                "account", "email", "password" -> {
                    if (action != "account" && details == null) { probe.complete(request); missing(); return@selected }
                    if (account == null) { probe.complete(request); missing(); return@selected }
                    if (!attempted.add(action)) { probe.complete(request); return@selected }
                    status(when (action) { "account" -> "Selecting your saved Microsoft account…"; "email" -> "Entering your university email…"; else -> "Submitting your university password…" })
                    // Mark before the click: navigation may discard the acknowledgement.
                    evaluate(LemidaCredentialScript.submit(action, account, details?.second.orEmpty())) {
                        if (current()) {
                            probe.complete(request)
                            if (it != "true") {
                                status("The sign-in page changed. Continue in the browser or edit Sign-in details.")
                                requiresInteraction()
                            }
                        }
                    }
                }
                "blocked" -> { probe.complete(request); status("Microsoft needs your attention. Complete the browser check or correct your Sign-in details."); requiresInteraction() }
                "mismatch" -> { probe.complete(request); status("Choose the Microsoft account matching your saved university email, or edit Sign-in details."); requiresInteraction() }
                "missing-account" -> { probe.complete(request); missing() }
                "waiting" -> probe.complete(request)
                else -> {
                    if (account != null) { probe.complete(request); continueMfa() }
                    else evaluate(LemidaCredentialScript.selectedAccount()) {
                        if (current()) {
                            val selected = runCatching { JSONTokener(it).nextValue() as? String }.getOrNull()
                            if (selected != null && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(selected)) rememberAccount(selected)
                            probe.complete(request); continueMfa()
                        }
                    }
                }
            }
        }
    }
}
