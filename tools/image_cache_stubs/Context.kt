// Standalone harness only; not an Android implementation.
package android.content
import java.io.File
class Context(val cacheDir: File) {
    val applicationContext: Context get() = this
}
