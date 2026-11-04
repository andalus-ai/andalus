package tech.kayys.andalus.execution.event.observability;

/** Low-cardinality classification of observation delivery failures. */
public enum ObservationDeliveryFailure {
    TRANSIENT,
    PERMANENT,
    UNKNOWN;

    public static ObservationDeliveryFailure classify(RuntimeException failure) {
        if (failure instanceof java.net.SocketTimeoutException
                || failure instanceof java.io.IOException
                || failure instanceof java.util.concurrent.TimeoutException) {
            return TRANSIENT;
        }
        if (failure instanceof IllegalArgumentException || failure instanceof SecurityException) {
            return PERMANENT;
        }
        return UNKNOWN;
    }
}
