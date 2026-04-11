package ed.unicoach.error

interface AppError {
    val rootCause: AppError?
}

data class ExceptionWrapper(val exception: Throwable) : AppError {
    override val rootCause: AppError? = null
    
    companion object {
        fun from(e: Throwable): ExceptionWrapper = ExceptionWrapper(e)
    }
}
