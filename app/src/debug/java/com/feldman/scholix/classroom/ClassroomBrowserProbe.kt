package com.feldman.scholix.classroom

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.StudentsPortalPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform
import org.json.JSONObject

/** Temporary on-device diagnostic, removed after verifying the browser login path. */
class ClassroomBrowserProbe : Activity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var submitted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setDataDirectorySuffix("classroom-probe-fresh")
        val provider = PlatformStorage.loadPlatforms(this).firstOrNull {
            it is StudentsPortalPlatform || (it is WebtopPlatform && it.isMoe())
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i("ClassroomBrowserProbe", "page=${url.toUri().host}${url.toUri().path}, title=${view.title}")
            }
        }
        setContentView(web)
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=classroom&continue=https%3A%2F%2Fclassroom.google.com%2F&Email=1002755976%40educ.org.il")
        handler.postDelayed(object : Runnable {
            override fun run() {
                val host = web.url?.toUri()?.host.orEmpty()
                if (host == "accounts.google.com") {
                    web.evaluateJavascript("""
                        (function(){
                          var e=document.querySelector('#identifierId'),b=document.querySelector('#identifierNext');
                          if(e&&b){if(!e.value)e.value='1002755976@educ.org.il';b.click();return 'identifier submitted';}
                          var t=document.body.innerText;
                          return /browser or app may not be secure|disallowed_useragent|not supported/i.test(t)?'unsupported browser':Array.from(document.querySelectorAll('button,input[type="submit"]')).map(b=>({text:b.innerText||b.value,id:b.id}));
                        })()
                    """.trimIndent()) { Log.i("ClassroomBrowserProbe", "Google page state=$it") }
                }
                if (host == "lgn.edu.gov.il" && !submitted && provider != null) {
                    val user = JSONObject.quote(provider.getUsername())
                    val pass = JSONObject.quote(provider.getPassword())
                    web.evaluateJavascript("""
                        (function(){
                          var u=document.querySelector('input[formcontrolname="userName"],input[name="userName"],input[type="text"]');
                          var p=document.querySelector('input[type="password"]');
                          if(!u||!p)return 'waiting';
                          u.value=$user;p.value=$pass;
                          [u,p].forEach(function(e){e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));});
                          var b=document.querySelector('button[type="submit"],form button');
                          if(!b)return 'waiting';b.click();return 'submitted';
                        })()
                    """.trimIndent()) { submitted = it == "\"submitted\"" }
                }
                if (host == "classroom.google.com") {
                    web.evaluateJavascript("document.querySelectorAll('a[href*=\"/c/\"]').length.toString()") {
                        Log.i("ClassroomBrowserProbe", "Classroom course links=$it")
                    }
                }
                handler.postDelayed(this, 1500)
            }
        }, 1500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
