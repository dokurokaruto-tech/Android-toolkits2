// Standalone harness only; minimal stand-in for android.content.Context.
package android.content

open class Context {
    val assets: android.content.res.AssetManager = android.content.res.AssetManager()
}
