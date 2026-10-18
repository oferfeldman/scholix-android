package com.feldman.scholix.lemida

import org.json.JSONObject

/** Bounded same-origin PDF fetch; small chunks cross the WebView bridge instead of one large data URL. */
internal object LemidaPdfScript {
    fun start(slot: String, url: String) = """(() => {
        if (location.origin !== '${LemidaParser.BASE}' || new URL(${JSONObject.quote(url)}).origin !== location.origin) return false;
        const state = {controller:new AbortController(), owner:location.href, result:null, bytes:null};
        window['$slot'] = state;
        (async () => {
          try {
            let r = await fetch(${JSONObject.quote(url)}, {credentials:'same-origin', signal:state.controller.signal});
            // Moodle resources may wrap an embedded PDF in an authenticated HTML page.
            if (new URL(r.url).origin === location.origin && new URL(${JSONObject.quote(url)}).pathname === '/mod/resource/view.php' &&
                /text\/html/i.test(r.headers.get('Content-Type') || '')) {
              const doc = new DOMParser().parseFromString(await r.text(), 'text/html');
              if (doc.body.classList.contains('notloggedin') || !doc.querySelector('a[href*="/login/logout.php"]')) {
                state.result = {verification:true}; return;
              }
              const candidates = [...doc.querySelectorAll('#region-main object[data], #region-main embed[src], #region-main iframe[src], #region-main a[href]')]
                .map(e => {try {return new URL(e.getAttribute('data') || e.getAttribute('src') || e.getAttribute('href'), r.url);} catch {return null;}})
                .filter(u => u && u.origin === location.origin && /^\/(pluginfile|file)\.php\//.test(u.pathname) && /\.pdf$/i.test(u.pathname));
              const files = [...new Map(candidates.map(u => [u.pathname, u])).values()];
              if (files.length !== 1) throw new Error('resource');
              r = await fetch(files[0].href, {credentials:'same-origin', signal:state.controller.signal});
            }
            if (new URL(r.url).origin !== location.origin || /text\/html/i.test(r.headers.get('Content-Type') || '')) {
              state.result = {verification:true}; await r.body?.cancel(); return;
            }
            if (!r.ok) throw new Error('http');
            if (Number(r.headers.get('Content-Length')) > ${LemidaPdf.MAX_BYTES}) throw new Error('size');
            const reader = r.body.getReader(), chunks = []; let size = 0;
            while (true) {
              const next = await reader.read(); if (next.done) break;
              size += next.value.length;
              if (size > ${LemidaPdf.MAX_BYTES}) { await reader.cancel(); throw new Error('size'); }
              chunks.push(next.value);
            }
            const bytes = new Uint8Array(size); let offset = 0;
            for (const chunk of chunks) {bytes.set(chunk, offset); offset += chunk.length;}
            state.bytes = bytes; state.result = {size};
          } catch (e) {state.result = {error:true, oversized:e.message === 'size'};}
        })(); return true;
    })()"""
    fun poll(slot: String) = """JSON.stringify(location.origin !== '${LemidaParser.BASE}' ? {verification:true} :
        !window['$slot'] || window['$slot'].owner !== location.href ? {lost:true} : window['$slot'].result)"""
    fun chunk(slot: String, offset: Int) = """(() => {
        const state = window['$slot'];
        if (location.origin !== '${LemidaParser.BASE}' || !state || state.owner !== location.href || !state.bytes) return null;
        return btoa(String.fromCharCode(...state.bytes.subarray($offset, $offset + 32768)));
    })()"""
}
