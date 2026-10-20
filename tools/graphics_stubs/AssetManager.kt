// Standalone harness only; minimal stand-in for android.content.res.AssetManager.
package android.content.res

class AssetManager {
    fun open(fileName: String): java.io.InputStream = throw java.io.FileNotFoundException(fileName)
}
