package com.buco7854.opentv.server

/** Framework-independent failures raised by application services. */
sealed class ApplicationError(message: String) : RuntimeException(message)

class ResourceNotFound(
    val resource: String,
    message: String = "No such $resource",
) : ApplicationError(message)
