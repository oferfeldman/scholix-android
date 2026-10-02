package com.feldman.scholix.lemida

object LemidaSms {
    /** Only a fresh Microsoft verification message may fill Microsoft's OTP field. */
    fun code(body: String, sender: String): String? {
        val microsoft = body.contains("Microsoft", true) || body.contains("מיקרוסופט") || sender.equals("Microsoft", true)
        val verification = Regex("verification|security code|authentication|אימות|קוד אבטחה", RegexOption.IGNORE_CASE).containsMatchIn(body)
        if (!microsoft || !verification) return null
        val candidates = Regex("(?<![0-9])([0-9]{6,8})(?![0-9])").findAll(body).map { it.groupValues[1] }.toList()
        return candidates.singleOrNull()
    }

    // Use the observed Authenticator alternative and Microsoft OneWaySMS picker.
    // The OTC field takes priority, so an existing SMS challenge is never resent.
    fun selectScript(alternativeClicked: Boolean, smsSelected: Boolean) = """
        (() => {
          if (location.origin !== 'https://login.microsoftonline.com') return 'other';
          delete window.__scholixMfaChoice;
          const visible = e => e && !!e.getClientRects().length && !['hidden', 'collapse'].includes(getComputedStyle(e).visibility);
          const enabled = e => visible(e) && !e.matches(':disabled') && !e.closest('[aria-disabled="true"], [inert]');
          const input = document.querySelector('#idTxtBx_SAOTCC_OTC');
          if (visible(input)) return enabled(input) && !input.readOnly ? 'otp' : 'otp-waiting';
          if (!$smsSelected) {
            const options = [...document.querySelectorAll('[data-value="OneWaySMS"], [data-authenticationmethod="OneWaySMS"]')];
            const fallback = [...document.querySelectorAll('[role="button"], button, a')].find(e => enabled(e) &&
              /^(Text\b|Send.*(?:text|SMS)|שלח.*(?:SMS|מסרון)|הודעת טקסט)/i.test(e.textContent.trim()));
            const sms = options.find(enabled) || fallback;
            if (sms) { window.__scholixMfaChoice = {element: sms, action: 'sms'}; return 'sms'; }
          }
          if (!$alternativeClicked) {
            const other = document.querySelector('#signInAnotherWay');
            if (enabled(other)) { window.__scholixMfaChoice = {element: other, action: 'alternative'}; return 'alternative'; }
          }
          return 'waiting';
        })()
    """.trimIndent()

    // The native caller records the attempt before this click can navigate the page.
    fun chooseScript(action: String): String {
        require(action in setOf("alternative", "sms"))
        return """
            (() => {
              if (location.origin !== 'https://login.microsoftonline.com') return false;
              const choice = window.__scholixMfaChoice;
              delete window.__scholixMfaChoice;
              if (!choice || choice.action !== '$action' || !choice.element.isConnected
                  || !choice.element.getClientRects().length || choice.element.matches(':disabled')
                  || choice.element.closest('[aria-disabled="true"], [inert]')
                  || ['hidden', 'collapse'].includes(getComputedStyle(choice.element).visibility)) return false;
              choice.element.click(); return true;
            })()
        """.trimIndent()
    }

    fun prepareCodeScript(code: String) = codeScript(code, click = false)

    fun submitScript(code: String) = codeScript(code, click = true)

    private fun codeScript(code: String, click: Boolean): String {
        require(Regex("[0-9]{6,8}").matches(code))
        return """
            (() => {
              if (location.origin !== 'https://login.microsoftonline.com') return false;
              const input = document.querySelector('#idTxtBx_SAOTCC_OTC');
              const submit = document.querySelector('#idSubmit_SAOTCC_Continue');
              const visible = e => e && !!e.getClientRects().length && !['hidden', 'collapse'].includes(getComputedStyle(e).visibility);
              const enabled = e => visible(e) && !e.matches(':disabled') && !e.closest('[aria-disabled="true"], [inert]');
              if (!enabled(input) || input.readOnly || !visible(submit)) return false;
              if (input.value !== '$code') {
                  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                  setter.call(input, '$code');
                  input.dispatchEvent(new Event('input', {bubbles: true}));
                  input.dispatchEvent(new Event('change', {bubbles: true}));
              }
              if (!input.isConnected || !submit.isConnected || !enabled(input) || input.readOnly || !enabled(submit)) return false;
              if ($click) submit.click();
              return true;
            })()
        """.trimIndent()
    }
}
