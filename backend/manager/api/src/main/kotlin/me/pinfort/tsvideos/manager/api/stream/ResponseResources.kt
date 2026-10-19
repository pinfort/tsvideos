package me.pinfort.tsvideos.manager.api.stream

import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resources that must outlive the controller because the response body is written after it returns. Everything
 * adopted here is closed by [ResponseResourcesFilter] once the whole request has been handled: after the body was
 * written, and also when the response had no body, the handler threw, writing failed or the client disconnected.
 */
class ResponseResources {
    private val adopted = mutableListOf<AutoCloseable>()
    private var closed = false

    /** Runs [open] and keeps its result for closing at the end of the request. */
    fun <T : AutoCloseable> adopt(open: () -> T): T {
        synchronized(this) { check(!closed) { "Request already completed" } }
        val resource = open()
        val kept = synchronized(this) { !closed && adopted.add(resource) }
        if (!kept) {
            // The request completed while opening; the close failure must not hide why the resource was refused.
            val completed = IllegalStateException("Request already completed")
            try {
                resource.close()
            } catch (e: Exception) {
                completed.addSuppressed(e)
            }
            throw completed
        }
        return resource
    }

    /** Closes every adopted resource, newest first; failures are logged so the rest are still closed. */
    internal fun close() {
        val resources =
            synchronized(this) {
                if (closed) return
                closed = true
                adopted.asReversed().toList().also { adopted.clear() }
            }
        resources.forEach { resource ->
            try {
                resource.close()
            } catch (e: Exception) {
                logger.warn("Failed to release a response resource", e)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ResponseResources::class.java)
        private val ATTRIBUTE = "${ResponseResources::class.java.name}.INSTANCE"

        /** The resources of [request]; fails before anything is opened if [ResponseResourcesFilter] is not installed. */
        fun of(request: HttpServletRequest): ResponseResources =
            checkNotNull(request.getAttribute(ATTRIBUTE) as? ResponseResources) { "ResponseResourcesFilter is not installed" }

        internal fun install(request: HttpServletRequest): ResponseResources =
            ResponseResources().also { request.setAttribute(ATTRIBUTE, it) }

        internal fun uninstall(request: HttpServletRequest) = request.removeAttribute(ATTRIBUTE)
    }
}

/**
 * Gives each request a [ResponseResources] and closes it when the request completes, however it completes. A
 * synchronous response (the video stream) is complete once the chain returns or throws: the body was written, writing
 * failed or the client disconnected. If a handler started async processing, the resources stay installed for the
 * async dispatches and are released when the async request completes. A timeout or error is not the end: the
 * container still dispatches error handling and then always completes the request, so only completion releases.
 *
 * It applies to every request rather than only the streaming endpoints: it is just a lightweight owner of the
 * request lifetime that does no NAS work until a handler adopts something, and owning every request the same way
 * keeps release consistent for synchronous and async handling without keeping an endpoint list in sync.
 */
@Component
class ResponseResourcesFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val resources = ResponseResources.install(request)
        var deferred = false
        try {
            filterChain.doFilter(request, response)
            if (request.isAsyncStarted) {
                request.asyncContext.addListener(ReleaseOnComplete(request, resources))
                deferred = true
            }
        } finally {
            if (!deferred) release(request, resources)
        }
    }

    private class ReleaseOnComplete(
        private val request: HttpServletRequest,
        private val resources: ResponseResources,
    ) : AsyncListener {
        override fun onComplete(event: AsyncEvent) = release(request, resources)

        override fun onTimeout(event: AsyncEvent) = Unit

        override fun onError(event: AsyncEvent) = Unit

        override fun onStartAsync(event: AsyncEvent) {
            // A restarted async cycle drops its listeners; keep waiting for the new one.
            event.asyncContext.addListener(this)
        }
    }

    private companion object {
        fun release(
            request: HttpServletRequest,
            resources: ResponseResources,
        ) {
            ResponseResources.uninstall(request)
            resources.close()
        }
    }
}
