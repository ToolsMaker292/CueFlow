package com.cueflow.app.ui.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Single process-wide CameraX entry point.
 *
 * Both the video preview card and the photo-OCR screen use this provider.
 * CameraX allows only one bound session at a time, so bind() unbinds the old
 * use cases first and release() only unbinds when the caller is still owner.
 */
object CameraRuntime {
    private var owner: Any? = null

    fun selectCamera(provider: ProcessCameraProvider, requested: Int): CameraSelector {
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        return when {
            requested == CameraSelector.LENS_FACING_FRONT && provider.hasCamera(front) -> front
            requested == CameraSelector.LENS_FACING_BACK && provider.hasCamera(back) -> back
            provider.hasCamera(back) -> back
            provider.hasCamera(front) -> front
            else -> throw IllegalStateException("No camera")
        }
    }

    suspend fun provider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            continuation.invokeOnCancellation { future.cancel(false) }
        }

    @Synchronized
    fun bind(
        owner: Any,
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        vararg useCases: UseCase,
    ): Camera {
        provider.unbindAll()
        this.owner = owner
        return provider.bindToLifecycle(lifecycleOwner, selector, *useCases)
    }

    @Synchronized
    fun release(owner: Any, provider: ProcessCameraProvider) {
        if (this.owner !== owner) return
        provider.unbindAll()
        this.owner = null
    }

}
