package ru.vtosters.lite.music.wave;

import com.vk.catalog2.core.CatalogConfiguration;
import com.vk.catalog2.core.CatalogEntryPointParams;
import com.vk.catalog2.core.api.dto.CatalogDataType;
import com.vk.catalog2.core.api.dto.CatalogViewType;
import com.vk.catalog2.core.blocks.UIBlock;
import com.vk.catalog2.core.blocks.UIBlockList;
import com.vk.catalog2.core.holders.common.CatalogViewHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WaveTabInjector {
    public static final String TAB_ID = "vt_wave";
    private static final String MUSIC_CATALOG = "com.vk.music.e.MusicCatalogConfiguration";

    private WaveTabInjector() {
    }

    public static void inject(List<UIBlock> tabs, CatalogConfiguration configuration) {
        if (tabs == null || configuration == null) {
            return;
        }
        if (!MUSIC_CATALOG.equals(configuration.getClass().getName())) {
            return;
        }

        for (UIBlock tab : tabs) {
            if (tab != null && TAB_ID.equals(tab.t1())) {
                return;
            }
        }

        UIBlockList wave = new UIBlockList(
                TAB_ID,
                CatalogViewType.LIST,
                CatalogDataType.DATA_TYPE_NONE,
                "Моя волна",
                0,
                Collections.emptyList(),
                false,
                "Моя волна",
                new ArrayList<>(),
                "",
                null,
                null,
                null
        );

        tabs.add(0, wave);
    }

    public static CatalogViewHolder maybeCreateHolder(UIBlock block, CatalogEntryPointParams params) {
        if (block != null && TAB_ID.equals(block.t1())) {
            return new WaveCatalogVh(params);
        }
        return null;
    }
}
