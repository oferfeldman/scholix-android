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
          if (location.hostname !== 'login.microsoftonline.com') return 'other';
          const visible = e => e && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
          if (visible(document.querySelector('#idTxtBx_SAOTCC_OTC'))) return 'otp';
          if (!$smsSelected) {
            const options = [...document.querySelectorAll('[data-value="OneWaySMS"], [data-authenticationmethod="OneWaySMS"]')];
            const fallback = [...document.querySelectorAll('[role="button"], button, a')].find(e =>
              /^(Text\b|Send.*(?:text|SMS)|שלח.*(?:SMS|מסרון)|הודעת טקסט)/i.test(e.textContent.trim()));
            const sms = options.find(visible) || (visible(fallback) ? fallback : null);
            if (sms) { sms.click(); return 'sms'; }
          }
          if (!$alternativeClicked) {
            const other = document.querySelector('#signInAnotherWay');
            if (visible(other)) { other.click(); return 'alternative'; }
          }
          return 'waiting';
        })()
    """.trimIndent()

    fun submitScript(code: String): String {
        require(Regex("[0-9]{6,8}").matches(code))
        return """
            (() => {
              if (location.hostname !== 'login.microsoftonline.com') return false;
              const input = document.querySelector('#idTxtBx_SAOTCC_OTC');
              const submit = document.querySelector('#idSubmit_SAOTCC_Continue');
              if (!input || !submit || !input.getClientRects().length) return false;
              const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
              setter.call(input, '$code');
              input.dispatchEvent(new Event('input', {bubbles: true}));
              input.dispatchEvent(new Event('change', {bubbles: true}));
              submit.click(); return true;
            })()
        """.trimIndent()
    }
}
