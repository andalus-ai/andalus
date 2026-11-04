package tech.kayys.andalus.execution.event.observability;

/** Low-cardinality classification of observation delivery failures. */
public enum ObservationDeliveryFailure {
    TRANSIENT,
    PERMANENT,
    UNKNOWN;

    /** Classifies the exception and its cause chain without depending on wrapper types. */
    public static ObservationDeliveryFailure classify(Throwable failure) {
        if (failure == null) return UNKNOWN;
        for (Throwable current = failure; current != null && current != current.getCause(); current = current.getCause()) {
            if (current instanceof java.io.IOException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketException) {
                return TRANSIENT;
            }
            if (current instanceof IllegalArgumentException || current instanceof SecurityException) {
                return PERMANENT;
            }
        }
        return UNKNOWN;
    }
}