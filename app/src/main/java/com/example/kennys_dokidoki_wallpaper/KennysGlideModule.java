package com.example.kennys_dokidoki_wallpaper;

import android.content.Context;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.module.AppGlideModule;

/**
 * 一覧のサムネを長く溜めすぎないよう、Glide の上限を端末ヒープより小さくする。
 */
@GlideModule
public final class KennysGlideModule extends AppGlideModule {
    @Override
    public void applyOptions(Context context, GlideBuilder builder) {
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(1.2f)
                .setBitmapPoolScreens(1.0f)
                .build();
        builder.setMemoryCache(new LruResourceCache(
                ImageMemoryPressurePolicy.INSTANCE.glideMemoryBytes(calculator.getMemoryCacheSize())
        ));
        builder.setBitmapPool(new LruBitmapPool(
                ImageMemoryPressurePolicy.INSTANCE.glideBitmapPoolBytes(calculator.getBitmapPoolSize())
        ));
        builder.setDiskCache(new InternalCacheDiskCacheFactory(
                context,
                ImageMemoryPressurePolicy.GLIDE_DISK_MAX_BYTES
        ));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
