package io.github.kromus.onnx

import cnames.structs.OrtEnv
import cnames.structs.OrtSessionOptions
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import onnxruntime.ORT_API_VERSION
import onnxruntime.OrtGetApiBase
import onnxruntime.OrtLoggingLevel
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the opt-in ONNX Runtime C bindings for real, rather than only compiling them.
 *
 * Registered only under `-Pkromus.onnxCApi`, and it links `libonnxruntime` itself — kromus binds
 * nothing, so a default build neither sees this file nor needs ORT at all.
 *
 * The C API is reached through `OrtGetApiBase()`, which hands back a struct of function pointers, and
 * every call goes through that indirection. It is the part of the interop that either works or does
 * not, and headers that merely parse prove nothing about it — so this allocates and releases real ORT
 * objects through those pointers.
 */
@OptIn(ExperimentalForeignApi::class)
class OnnxCApiInteropTest {

    @Test
    fun theApiBaseReportsARuntimeVersion() {
        val base = assertNotNull(OrtGetApiBase(), "OrtGetApiBase() returned null").pointed
        val version = assertNotNull(base.GetVersionString!!.invoke(), "no version string").toKString()
        assertTrue(version.isNotEmpty(), "ORT reported an empty version string")
    }

    @Test
    fun anEnvironmentAndSessionOptionsRoundTrip() {
        val api = assertNotNull(
            OrtGetApiBase()!!.pointed.GetApi!!.invoke(ORT_API_VERSION.toUInt()),
            "GetApi(ORT_API_VERSION) returned null — the headers built against and the linked library disagree",
        ).pointed

        memScoped {
            val env = alloc<CPointerVar<OrtEnv>>()
            assertNull(
                api.CreateEnv!!.invoke(
                    OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,
                    "kromus".cstr.ptr,
                    env.ptr,
                ),
                "CreateEnv returned a status, meaning it failed",
            )
            assertNotNull(env.value, "CreateEnv reported success but produced no environment")

            val options = alloc<CPointerVar<OrtSessionOptions>>()
            assertNull(
                api.CreateSessionOptions!!.invoke(options.ptr),
                "CreateSessionOptions returned a status, meaning it failed",
            )
            assertNotNull(options.value, "CreateSessionOptions reported success but produced nothing")

            api.ReleaseSessionOptions!!.invoke(options.value)
            api.ReleaseEnv!!.invoke(env.value)
        }
    }
}
