package top.yukonga.mishka.service

/**
 * Serializes service start requests without depending on Android Service or Job.
 * A fresh request may replace an attach-only request, but never another fresh request.
 */
internal class ServiceStartStateMachine {
    internal data class Token(val id: Long, val attachOnly: Boolean)

    internal data class Request(
        val token: Token,
        val superseded: Token? = null,
    )

    private var nextId = 0L
    private var active: Token? = null

    @Synchronized
    fun request(attachOnly: Boolean): Request? {
        val current = active
        if (current != null) {
            if (attachOnly || !current.attachOnly) return null
            val token = Token(++nextId, attachOnly = false)
            active = token
            return Request(token, superseded = current)
        }
        val token = Token(++nextId, attachOnly)
        active = token
        return Request(token)
    }

    @Synchronized
    fun complete(token: Token) {
        if (active == token) active = null
    }
}
