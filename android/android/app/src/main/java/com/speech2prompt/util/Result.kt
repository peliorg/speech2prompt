package com.speech2prompt.util

/**
 * Sealed class for representing operation results.
 * Provides type-safe success/error handling without exceptions.
 */
sealed class Result<out T> {
    /**
     * Successful result with data
     */
    data class Success<T>(val data: T) : Result<T>()

    /**
     * Error result with message and optional exception
     */
    data class Error(
        val message: String,
        val exception: Throwable? = null,
        val errorCode: Int? = null
    ) : Result<Nothing>()

    /**
     * Whether the result is successful
     */
    val isSuccess: Boolean
        get() = this is Success

    /**
     * Whether the result is an error
     */
    val isError: Boolean
        get() = this is Error

    /**
     * Get data if successful, null otherwise
     */
    fun getOrNull(): T? {
        return when (this) {
            is Success -> data
            is Error -> null
        }
    }

    /**
     * Get data if successful, or default value if error
     */
    fun getOrDefault(defaultValue: @UnsafeVariance T): T {
        return when (this) {
            is Success -> data
            is Error -> defaultValue
        }
    }

    /**
     * Get data if successful, or compute default value if error
     */
    inline fun getOrElse(defaultValue: (Error) -> @UnsafeVariance T): T {
        return when (this) {
            is Success -> data
            is Error -> defaultValue(this)
        }
    }

    /**
     * Execute block if successful
     */
    inline fun onSuccess(block: (T) -> Unit): Result<T> {
        if (this is Success) {
            block(data)
        }
        return this
    }

    /**
     * Execute block if error
     */
    inline fun onError(block: (Error) -> Unit): Result<T> {
        if (this is Error) {
            block(this)
        }
        return this
    }

    /**
     * Transform successful result
     */
    inline fun <R> map(transform: (T) -> R): Result<R> {
        return when (this) {
            is Success -> Success(transform(data))
            is Error -> this
        }
    }

    /**
     * Transform successful result with another Result
     */
    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> {
        return when (this) {
            is Success -> transform(data)
            is Error -> this
        }
    }

    /**
     * Transform error
     */
    inline fun mapError(transform: (Error) -> Error): Result<T> {
        return when (this) {
            is Success -> this
            is Error -> transform(this)
        }
    }

    companion object {
        /**
         * Create a successful result
         */
        fun <T> success(data: T): Result<T> = Success(data)

        /**
         * Create an error result
         */
        fun error(
            message: String,
            exception: Throwable? = null,
            errorCode: Int? = null
        ): Result<Nothing> = Error(message, exception, errorCode)

        /**
         * Execute a block and wrap result in Result
         */
        inline fun <T> runCatching(block: () -> T): Result<T> {
            return try {
                Success(block())
            } catch (e: Exception) {
                Error(e.message ?: "Unknown error", e)
            }
        }

        /**
         * Execute a suspending block and wrap result in Result
         */
        suspend inline fun <T> runCatchingSuspend(crossinline block: suspend () -> T): Result<T> {
            return try {
                Success(block())
            } catch (e: Exception) {
                Error(e.message ?: "Unknown error", e)
            }
        }
    }
}

/**
 * Extension to convert nullable values to Result
 */
fun <T> T?.toResult(errorMessage: String = "Value is null"): Result<T> {
    return if (this != null) {
        Result.success(this)
    } else {
        Result.error(errorMessage)
    }
}

/**
 * Extension to convert Throwable to Error Result
 */
fun Throwable.toErrorResult(): Result.Error {
    return Result.Error(
        message = this.message ?: "Unknown error",
        exception = this
    )
}
