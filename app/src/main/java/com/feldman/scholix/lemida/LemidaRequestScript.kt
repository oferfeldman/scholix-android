package com.feldman.scholix.lemida

import org.json.JSONObject

/** One browser request, including its abort handle, lives only in the current document. */
internal object LemidaRequestScript {
    fun signedIn(url: String) = """(() => location.origin === '${LemidaParser.BASE}'
        && location.href === ${JSONObject.quote(url)}
        && !!document.body && !document.body.classList.contains('notloggedin')
        && Number(window.M?.cfg?.userId) > 1
        && !!document.querySelector('a[href*="/login/logout.php"]'))()"""

    fun document(url: String) = """(() =>
        location.origin === '${LemidaParser.BASE}' && location.href === ${JSONObject.quote(url)}
            ? document.documentElement.outerHTML : null
    )()"""

    fun start(slot: String, url: String, body: String) = """(() => {
        if (location.origin !== '${LemidaParser.BASE}' || new URL(${JSONObject.quote(url)}).origin !== location.origin) return false;
        const state = {controller: new AbortController(), result: null};
        window['$slot'] = state;
        fetch(${JSONObject.quote(url)}, {method: 'POST', credentials: 'same-origin', signal: state.controller.signal,
            headers: {'Content-Type': 'application/json'}, body: ${JSONObject.quote(body)}})
          .then(async r => {
              const result = {status:r.status, url:r.url, body:await r.text()};
              if (window['$slot'] === state) state.result = result;
          })
          .catch(() => {if (window['$slot'] === state) state.result = {error:true};});
        return true;
    })()"""

    fun poll(slot: String) = """JSON.stringify(
        location.origin !== '${LemidaParser.BASE}' ? {verification:true} :
        !window['$slot'] ? {lost:true} : window['$slot'].result
    )"""

    fun cleanup(slot: String) = """(() => {
        const state = window['$slot'];
        delete window['$slot'];
        if (state) state.controller.abort();
    })()"""
}
