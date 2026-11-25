// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.state;

import android.os.Handler;
import android.os.Looper;

import ru.vtosters.lite.core.log.VtLog;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal {@link Store} implementation for the F-03 contracts: values are published on the main
 * thread, callers may update it from background threads.
 */
public final class MutableStore<T> implements Store<T> {

    private static final String TAG = "Store";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener<T>> listeners = new CopyOnWriteArrayList<>();
    private volatile T value;

    public MutableStore(T initialValue) {
        this.value = initialValue;
    }

    @Override
    public T get() {
        return value;
    }

    @Override
    public void subscribe(Listener<T> listener) {
        if (listener == null) {
            return;
        }
        listeners.addIfAbsent(listener);
        T current = value;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onChanged(current);
        } else {
            mainHandler.post(() -> listener.onChanged(current));
        }
    }

    @Override
    public void unsubscribe(Listener<T> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void set(T newValue) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            value = newValue;
            notifyListeners(newValue);
        } else {
            mainHandler.post(() -> {
                value = newValue;
                notifyListeners(newValue);
            });
        }
    }

    private void notifyListeners(T newValue) {
        for (Listener<T> listener : listeners) {
            try {
                listener.onChanged(newValue);
            } catch (Throwable t) {
                VtLog.e(TAG, "listener failed", t);
            }
        }
    }
}
