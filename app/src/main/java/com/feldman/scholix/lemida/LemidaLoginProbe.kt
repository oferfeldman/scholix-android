package com.feldman.scholix.lemida

/** A probe belongs to one document; navigation must release a lost JS callback. */
internal class LemidaLoginProbe {
    private var serial = 0
    private var active: Int? = null

    fun start(): Int? {
        if (active != null) return null
        return (++serial).also { active = it }
    }

    fun complete(request: Int): Boolean {
        if (active != request) return false
        active = null
        return true
    }

    fun invalidate() { active = null }
}
