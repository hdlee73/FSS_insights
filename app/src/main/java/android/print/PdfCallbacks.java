package android.print;

/**
 * Instantiation bridge for WebView print callbacks.
 * The framework constructors are public at runtime, but SDK stubs expose them
 * only inside android.print. Keep the bridge small and verify it on a device.
 */
public final class PdfCallbacks {
    private PdfCallbacks() {}

    public abstract static class Layout extends PrintDocumentAdapter.LayoutResultCallback {
        public Layout() { super(); }
    }

    public abstract static class Write extends PrintDocumentAdapter.WriteResultCallback {
        public Write() { super(); }
    }
}
