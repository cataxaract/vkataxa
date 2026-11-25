// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.state;

/**
 * Minimal observable state without coroutines (F-03).
 * Implementations must mutate the value on the main thread and notify listeners on it.
 */
public interface Store<T> {

    T get();

    void subscribe(Listener<T> listener);

    void unsubscribe(Listener<T> listener);

    interface Listener<T> {
        void onChanged(T value);
    }
}
