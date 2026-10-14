package com.feldman.scholix.lemida

import org.json.JSONObject

/** Credential entry is confined to Microsoft's top-level HTTPS document. */
internal object LemidaCredentialScript {
    fun probe(email: String?) = script("probe", email, null)
    fun selectedAccount() = """
        (() => {
          if (window.top !== window || location.origin !== 'https://login.microsoftonline.com') return null;
          const display = document.querySelector('#displayName');
          if (!display || !display.getClientRects().length || ['hidden', 'collapse'].includes(getComputedStyle(display).visibility)) return null;
          const email = display.textContent.trim();
          return /^[^\s@]+@[^\s@]+\.[^\s@]+${'$'}/.test(email) ? email : null;
        })()
    """.trimIndent()
    fun submit(action: String, email: String, password: String): String {
        require(action in setOf("account", "email", "password"))
        return script(action, email, if (action == "password") password else null)
    }

    private fun script(action: String, email: String?, password: String?): String {
        val emailJson = email?.let(JSONObject::quote) ?: "null"
        val passwordJson = password?.let(JSONObject::quote) ?: "null"
        return """
            (() => {
              const action = '$action', email = $emailJson, password = $passwordJson;
              const result = value => action === 'probe' ? value : false;
              if (window.top !== window || location.origin !== 'https://login.microsoftonline.com') return result('other');
              const visible = e => e && !!e.getClientRects().length && !['hidden', 'collapse'].includes(getComputedStyle(e).visibility);
              const enabled = e => visible(e) && !e.matches(':disabled') && !e.closest('[aria-disabled="true"], [inert]');
              const captcha = [...document.querySelectorAll('[id*="captcha" i], iframe[src*="captcha" i], iframe[src*="arkose" i]')].some(visible);
              const error = [...document.querySelectorAll('#passwordError, #usernameError, #errorText')].some(e => visible(e) && e.textContent.trim());
              if (captcha || error) return result('blocked');
              const display = document.querySelector('#displayName');
              const normalize = value => value.trim().toLowerCase();
              if (email && visible(display) && normalize(display.textContent) !== normalize(email)) return result('mismatch');
              const picker = document.querySelector('#tilesHolder');
              if (visible(picker)) {
                // Microsoft's account component binds session.unsafe_name to data-test-id.
                const matches = [...picker.querySelectorAll('[role="button"][data-test-id]')]
                  .filter(e => enabled(e) && email && normalize(e.getAttribute('data-test-id')) === normalize(email));
                if (matches.length !== 1) return result('missing-account');
                if (action === 'probe') return 'account';
                if (action !== 'account') return false;
                matches[0].click(); return true;
              }
              const emailField = document.querySelector('#i0116, input[name="loginfmt"]');
              const passwordField = document.querySelector('#i0118');
              const next = document.querySelector('#idSIButton9');
              let kind, input, value;
              if (visible(passwordField)) {
                const account = visible(display) ? display.textContent : '';
                if (email && normalize(account) !== normalize(email)) return result('mismatch');
                kind = 'password'; input = passwordField; value = password;
              } else if (visible(emailField)) {
                kind = 'email'; input = emailField; value = email;
                if (email && input.value && normalize(input.value) !== normalize(email)) return result('mismatch');
              } else return result('other');
              if (!enabled(input) || input.readOnly || !enabled(next)) return result('waiting');
              if (action === 'probe') return kind;
              if (kind !== action || !value || (input.value && (kind === 'password' ? input.value !== value : normalize(input.value) !== normalize(value)))) return false;
              const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
              setter.call(input, value);
              input.dispatchEvent(new Event('input', {bubbles: true}));
              input.dispatchEvent(new Event('change', {bubbles: true}));
              if (!input.isConnected || !next.isConnected || !enabled(input) || input.readOnly || !enabled(next) || input.value !== value) return false;
              next.click(); return true;
            })()
        """.trimIndent()
    }
}
