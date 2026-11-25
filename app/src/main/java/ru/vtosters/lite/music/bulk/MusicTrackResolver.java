// SPDX-License-Identifier: MIT
package ru.vtosters.lite.music.bulk;

import androidx.recyclerview.widget.RecyclerView;

import com.vk.catalog2.core.blocks.UIBlockMusicTrack;
import com.vk.dto.music.MusicTrack;
import com.vk.lists.PaginatedRecyclerAdapter;
import com.vk.lists.SimpleAdapter;
import com.vk.music.common.MusicTrackModel;
import com.vk.music.playlist.modern.adapters.MultiPartTracksMergedAdapter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MU-02: extracts the tracks backing a VK music RecyclerView without touching its adapter.
 */
final class MusicTrackResolver {

    private MusicTrackResolver() {
    }

    static List<MusicTrack> tracks(RecyclerView recyclerView) {
        List<MusicTrack> result = new ArrayList<>();
        if (recyclerView == null) {
            return result;
        }
        collect(recyclerView.getAdapter(), result);

        Map<String, MusicTrack> unique = new LinkedHashMap<>();
        for (MusicTrack track : result) {
            String id = idOf(track);
            if (id != null) {
                unique.put(id, track);
            }
        }
        return new ArrayList<>(unique.values());
    }

    static MusicTrack trackAt(RecyclerView recyclerView, int position) {
        if (recyclerView == null || position < 0) {
            return null;
        }
        return trackAt(recyclerView.getAdapter(), position);
    }

    private static void collect(RecyclerView.Adapter<?> adapter, List<MusicTrack> out) {
        if (adapter instanceof MultiPartTracksMergedAdapter) {
            List<MusicTrack> tracks = ((MultiPartTracksMergedAdapter) adapter).l();
            if (tracks != null) {
                out.addAll(tracks);
            }
            return;
        }
        if (adapter instanceof PaginatedRecyclerAdapter) {
            collect(((PaginatedRecyclerAdapter<?>) adapter).a, out);
            return;
        }
        if (adapter instanceof SimpleAdapter) {
            List<?> items = ((SimpleAdapter<?, ?>) adapter).f();
            if (items == null) {
                return;
            }
            for (Object item : items) {
                MusicTrack track = toTrack(item);
                if (track != null) {
                    out.add(track);
                }
            }
        }
    }

    private static MusicTrack trackAt(RecyclerView.Adapter<?> adapter, int position) {
        if (adapter instanceof MultiPartTracksMergedAdapter) {
            return ((MultiPartTracksMergedAdapter) adapter).K(position);
        }
        if (adapter instanceof PaginatedRecyclerAdapter) {
            return trackAt(((PaginatedRecyclerAdapter<?>) adapter).a, position);
        }
        if (adapter instanceof SimpleAdapter) {
            try {
                return toTrack(((SimpleAdapter<?, ?>) adapter).k(position));
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static MusicTrack toTrack(Object item) {
        if (item instanceof MusicTrack) {
            return (MusicTrack) item;
        }
        if (item instanceof MusicTrackModel) {
            return ((MusicTrackModel) item).k0();
        }
        if (item instanceof UIBlockMusicTrack) {
            return ((UIBlockMusicTrack) item).D1();
        }
        if (item == null) {
            return null;
        }

        for (String methodName : new String[]{"k0", "S", "getTrack"}) {
            try {
                Method method = item.getClass().getMethod(methodName);
                if (MusicTrack.class.isAssignableFrom(method.getReturnType())) {
                    Object value = method.invoke(item);
                    if (value instanceof MusicTrack) {
                        return (MusicTrack) value;
                    }
                }
            } catch (Throwable ignored) {
                // try the next accessor
            }
        }
        return null;
    }

    private static String idOf(MusicTrack track) {
        if (track == null) {
            return null;
        }
        String id = track.y1();
        return id == null || id.isEmpty() ? null : id;
    }
}
