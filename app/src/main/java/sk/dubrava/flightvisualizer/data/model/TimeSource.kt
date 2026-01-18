package sk.dubrava.flightvisualizer.data.model

/**
 * Určuje, odkiaľ pochádza časová os (dt / timestamp) medzi bodmi letu.
 */
enum class TimeSource {

    /** Skutočný čas z logu (timestamp, time_ms, atď.). */
    REAL_TIMESTAMP,

    /** Čas je dopočítaný z pevnej vzorkovacej frekvencie (napr. 10 Hz). */
    FIXED_RATE,

    /** Čas pochádza z KML Tour (gx:duration) – vizualizačný čas, nie fyzika letu. */
    KML_TOUR_DURATION
}


