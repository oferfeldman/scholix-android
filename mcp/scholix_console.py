"""Scholix account setup in the existing HTTPS owner console."""
import asyncio
import html
import json
import secrets

from fastapi import HTTPException, Request
from fastapi.responses import RedirectResponse, Response

from gateway.scholix import PROVIDERS
from gateway import scholix_browser, scholix_google


def connection_html(service, prefix, token):
    escape = lambda value: html.escape(str(value), quote=True)
    hidden = '<input type=hidden name=csrf value="' + escape(token) + '">'
    cards = []
    for key, account in service.connections.items():
        key_html = escape(key)
        state = "Connected" if account.get("verified") else "Verify SMS" if account.get("pending_sms") else "Finish sign-in"
        status_class = "connected" if account.get("verified") else "needs-sign-in"
        card = f'<motion-card class="connection-card"><span class="service-icon symbol" aria-hidden=true>school</span><div class=heading-copy><bdi>{escape(account["title"])}</bdi><span class=status>{escape(PROVIDERS[account["provider"]]["title"])} · <span class="connection-auth {status_class}">{state}</span></span></div><form method=post action="{prefix}/admin/scholix/disconnect">{hidden}<input type=hidden name=connection_id value="{key_html}"><button class="danger icon-button" data-icon=link_off aria-label="Disconnect {escape(account["title"])}">Disconnect</button></form></motion-card>'
        if account.get("pending_sms"):
            card += f'<form class=account-form method=post action="{prefix}/admin/scholix/sms">{hidden}<input type=hidden name=connection_id value="{key_html}"><label>SMS code<input type=password inputmode=numeric name=code autocomplete=one-time-code required></label><button data-icon=verified>Verify code</button></form>'
        if account.get("browser"):
            card += f'<p><a href="{prefix}/admin/scholix/browser/{key_html}">Open sign-in browser</a></p>'
        cards.append(card)
    options = ''.join(f'<option value="{key}">{escape(info["title"])}</option>' for key, info in PROVIDERS.items() if key != "openu")
    return f'''<div class=connection-stack>{''.join(cards)}</div>
<details class=add-account><summary>Add a Scholix account</summary>
<form class=account-form method=post action="{prefix}/admin/scholix/connect" data-busy="Connecting…">{hidden}
<label>Provider<select name=provider>{options}</select></label>
<label>Account name<input name=title autocomplete=off></label>
<label>Sign-in method<select name=method><option value=password>Password</option><option value=moe>Education Ministry password</option><option value=sms>SMS</option><option value=browser>Interactive browser</option></select></label>
<label>Username / ID<input name=username autocomplete=username></label><label>Password<input type=password name=password autocomplete=current-password></label>
<label>Registered mobile (SMS)<input name=mobile inputmode=tel autocomplete=tel></label>
<label>Mashov school code<input name=school_code inputmode=numeric></label><label>Mashov school year<input name=school_year inputmode=numeric></label>
<small>Inbar uses SMS. Education Portal and Lemida use the interactive browser. Google Classroom/Drive use Google OAuth. Demo needs no credentials.</small>
<button data-icon=add>Connect provider</button></form></details>
<details class=add-account><summary>Scholix connection settings</summary>
<p>Google OAuth callback: <code>{escape(service.public_url)}/admin/scholix/google/callback</code></p>
<form class=account-form method=post action="{prefix}/admin/scholix/google/config">{hidden}<label>Google Web OAuth client JSON<textarea name=client_json autocomplete=off required></textarea></label><button>Save Google OAuth client</button></form>
<details><summary>Import an existing Scholix provider session</summary><form class=account-form method=post action="{prefix}/admin/scholix/import">{hidden}<label>Provider<select name=provider>{options}</select></label><label>Account name<input name=title></label><label>Provider session JSON<textarea name=state autocomplete=off required></textarea></label><small>Private provider state stays encrypted. Device-encrypted Inbar sessions require a fresh SMS sign-in.</small><button>Import session</button></form></details></details>'''


def install(app, service, prefix, public_url, render_page, owner_session, check_form_origin, csrf):
    service.public_url = public_url

    async def form(request):
        session = owner_session(request)
        check_form_origin(request)
        values = await request.form()
        if not secrets.compare_digest(str(values.get("csrf", "")), csrf(session, "account")):
            raise HTTPException(403, "Invalid form token")
        return session, values

    def back(): return RedirectResponse(public_url + "/#scholix", status_code=303)
    def failure(message):
        return render_page("Scholix connection", '<p>' + html.escape(str(message)) + '</p><a href="' + prefix + '/#scholix">Back to connections</a>')

    @app.post(prefix + "/admin/scholix/connect", include_in_schema=False)
    async def connect(request: Request):
        session, values = await form(request)
        provider, method = str(values.get("provider", "")), str(values.get("method", "password"))
        fields = {key: str(values.get(key, "")) for key in ("username", "password", "mobile", "school_code", "school_year", "method")}
        try:
            if provider in {"classroom", "drive"}:
                url = scholix_google.start(service, provider, public_url, session)
                return render_page("Connect Google", f'<p>Continue in Google to choose your account and approve access.</p><a href="{html.escape(url, quote=True)}">Continue to Google</a>')
            if method == "browser" or provider in {"education", "lemida"}:
                key = await scholix_browser.start(service, provider, str(values.get("title", "")))
                return RedirectResponse(public_url + "/admin/scholix/browser/" + key, status_code=303)
            await service.connect(provider, str(values.get("title", "")), fields)
            return back()
        except Exception as error:
            return failure(str(error) if isinstance(error, ValueError) else "Provider sign-in could not be completed. Check the details and selected sign-in method.")

    @app.post(prefix + "/admin/scholix/sms", include_in_schema=False)
    async def verify_sms(request: Request):
        _, values = await form(request)
        try:
            await service.verify_sms(str(values.get("connection_id", "")), str(values.get("code", "")))
            return back()
        except Exception:
            return failure("The SMS code was not accepted or the challenge expired. Correct it in the console; after a server restart, start a fresh SMS sign-in.")

    @app.post(prefix + "/admin/scholix/disconnect", include_in_schema=False)
    async def disconnect(request: Request):
        _, values = await form(request)
        await service.disconnect(str(values.get("connection_id", "")))
        return back()

    @app.post(prefix + "/admin/scholix/import", include_in_schema=False)
    async def import_session(request: Request):
        _, values = await form(request)
        try:
            await service.import_account(str(values.get("provider", "")), str(values.get("title", "")), json.loads(str(values.get("state", ""))))
            return back()
        except Exception:
            return failure("The provider did not accept this saved session. Use a fresh sign-in if it expired or is device-encrypted.")

    @app.post(prefix + "/admin/scholix/google/config", include_in_schema=False)
    async def google_config(request: Request):
        _, values = await form(request)
        try:
            scholix_google.configure(service, str(values.get("client_json", "")))
            return back()
        except Exception:
            return failure("Use the downloaded JSON for a Google Web application OAuth client with the displayed callback URL.")

    @app.get(prefix + "/admin/scholix/google/callback", include_in_schema=False)
    async def google_callback(request: Request):
        session = owner_session(request)
        try:
            await asyncio.to_thread(scholix_google.complete, service, request.query_params.get("state", ""), request.query_params.get("code", ""), session)
            return back()
        except Exception:
            return failure("Google sign-in was cancelled, expired, or did not grant an offline session. Try connecting again.")

    @app.get(prefix + "/admin/scholix/browser/{key}", include_in_schema=False)
    async def browser_console(key: str, request: Request):
        session = owner_session(request)
        if key not in service.connections or not service.connections[key].get("browser"):
            raise HTTPException(404, "Unknown browser connection")
        browser = await scholix_browser.browser_for(service, key)
        pages = (await browser._pages())["pages"]
        options = ''.join(f'<option value="{item["page_id"]}">{item["page_id"]}: {html.escape(item["title"])}</option>' for item in pages)
        page_id = int(request.query_params.get("page_id", pages[-1]["page_id"]))
        page = browser._page({"page_id": page_id})
        controls = []
        if not browser.dialogs:
            for index, frame in enumerate(page.frames):
                try:
                    browser.validate_url(frame.url)
                    elements = await frame.locator("body").evaluate('''root => [...root.querySelectorAll('input,button,select,textarea,a[href]')].filter(e=>e.type!=='hidden'&&e.getClientRects().length).slice(0,150).map(e=>({selector:e.id?'#'+CSS.escape(e.id):e.name?e.localName+'[name='+JSON.stringify(e.name)+']':(()=>{let p=[];for(let n=e;n&&n.nodeType===1;n=n.parentElement){p.unshift(n.localName+(n.parentElement?':nth-child('+([...n.parentElement.children].indexOf(n)+1)+')':''))}return p.join(' > ')})(),name:e.type==='password'?'Password':e.getAttribute('aria-label')||e.innerText||e.name||e.type}))''')
                    controls += [f'<li>Frame {index}: {html.escape(element["name"][:160])} <code>{html.escape(element["selector"])}</code></li>' for element in elements]
                except Exception:
                    continue
        token = csrf(session, "account")
        pending = browser.dialogs.get(page)
        image = '<p>Confirmation: ' + html.escape(pending.message) + '</p>' if pending else f'<img src="{prefix}/admin/scholix/browser/{key}/image?page_id={page_id}" style="width:100%;height:auto" alt="Provider sign-in browser">'
        result = render_page("Scholix sign-in", f'''<p>Complete sign-in and any verification here. Passwords/codes stay between this console and the provider.</p>{image}
<form method=get><label>Tab<select name=page_id>{options}</select></label><button>View tab</button></form>
<form method=post action="{prefix}/admin/scholix/browser/{key}"><input type=hidden name=csrf value="{token}"><input type=hidden name=page_id value="{page_id}">
<label>Action<select name=action><option value=fill>Enter a field</option><option value=click>Click</option><option value=select>Select option</option><option value=press>Press key</option><option value=accept>Accept confirmation</option><option value=dismiss>Dismiss confirmation</option><option value=complete>Complete connection</option></select></label>
<label>Frame<input name=frame type=number value=0 min=0></label><label>Element selector<input name=selector autocomplete=off></label><label>Value / password / code<input type=password name=value autocomplete=off></label><button>Continue</button></form>
<details><summary>Available controls</summary><ul>{''.join(controls)}</ul></details><p><a href="{prefix}/#scholix">Back to Scholix</a></p>''')
        result.headers["Content-Security-Policy"] += "; img-src 'self'"
        return result

    @app.get(prefix + "/admin/scholix/browser/{key}/image", include_in_schema=False)
    async def browser_image(key: str, request: Request):
        owner_session(request)
        if key not in service.connections: raise HTTPException(404, "Unknown connection")
        browser = await scholix_browser.browser_for(service, key)
        page = browser._page({"page_id": int(request.query_params.get("page_id", "0"))})
        if page in browser.dialogs: raise HTTPException(409, "Resolve the pending confirmation first")
        return Response(await page.screenshot(), media_type="image/png", headers={"Cache-Control": "no-store"})

    @app.post(prefix + "/admin/scholix/browser/{key}", include_in_schema=False)
    async def browser_control(key: str, request: Request):
        _, values = await form(request)
        if key not in service.connections: raise HTTPException(404, "Unknown connection")
        try:
            browser = await scholix_browser.browser_for(service, key)
            action = str(values.get("action", ""))
            page_id = int(values.get("page_id", "0"))
            if action == "complete":
                await scholix_browser.complete(service, key)
                return back()
            if action in {"accept", "dismiss"}:
                await browser.invoke("dialog", {"page_id": page_id, "accept": action == "accept"})
            else:
                page = browser._page({"page_id": page_id})
                frame = page.frames[int(values.get("frame", "0"))]
                browser.validate_url(frame.url)
                locator = frame.locator(str(values.get("selector", "")))
                if await locator.count() != 1: raise ValueError("Select exactly one control using its selector and frame")
                value = str(values.get("value", ""))
                if action == "fill": await browser._action(page, locator.fill(value, timeout=15000))
                elif action == "click": await browser._action(page, locator.click(timeout=15000, no_wait_after=True))
                elif action == "select": await browser._action(page, locator.select_option(value, timeout=15000))
                elif action == "press": await browser._action(page, locator.press(value, timeout=15000, no_wait_after=True))
                else: raise ValueError("Unknown browser action")
                browser.saved["storage_state"]["cookies"] = await browser.context.cookies()
                service.store.put("account", browser.slug, browser.saved)
            return RedirectResponse(public_url + "/admin/scholix/browser/" + key + "?page_id=" + str(page_id), status_code=303)
        except Exception as error:
            return failure(str(error) if isinstance(error, ValueError) else "The browser action did not complete. Inspect the provider page before retrying.")
