# Fix Unresolved Reference 'render' in BulkThumbnailDialog

The `render()` local function is currently defined after it is used in the `BulkThumbnailAdapter` initialization, causing a build error in Kotlin because local functions are not hoisted.

## Proposed Changes

### [BulkThumbnailDialog](file:///C:/Users/user/Documents/GitHub/Android-toolkits2/app/src/main/java/com/example/kennys_dokidoki_wallpaper/BulkThumbnailDialog.kt)

#### [MODIFY] [BulkThumbnailDialog.kt](file:///C:/Users/user/Documents/GitHub/Android-toolkits2/app/src/main/java/com/example/kennys_dokidoki_wallpaper/BulkThumbnailDialog.kt)
- Declare `adapter` as `lateinit var` to allow forward reference in local functions.
- Move `currentFilter()` and `render()` local functions above the `adapter` initialization.
- Ensure all dependencies of `render()` (like `tvSummary`, `btnSelectAll`, etc.) are available when it's defined.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify that the project builds successfully.

### Manual Verification
- None required for this structural fix, but ensuring the build passes is the primary goal.
