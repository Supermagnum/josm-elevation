package no.nvdbincline.core;

/** Optional progress / cancel hook for long core compute (matching, gradients). */
@FunctionalInterface
public interface ProgressCallback {
    /**
     * @param phase short status text (English; UI may translate or pass through)
     * @param done completed units
     * @param total total units (may be 0 if unknown)
     * @return {@code false} to cancel the operation
     */
    boolean onProgress(String phase, int done, int total);

    ProgressCallback NONE = (phase, done, total) -> true;
}
