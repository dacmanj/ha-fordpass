/**
 * FordPass Vehicle — Hubitat Driver
 *
 * Ported from marq24/ha-fordpass (Home Assistant custom integration).
 * Source: https://github.com/marq24/ha-fordpass
 *
 * Child device created and managed by the FordPass Connect app.
 * The app calls parseVehicleData(Map) with the raw API JSON each poll cycle.
 * Commands relay back to the app via parent.sendVehicleCommand().
 *
 * Capabilities exposed:
 *   Lock                — door lock/unlock
 *   Switch              — remote start (on = start, off = cancel)
 *   Refresh             — trigger a manual data refresh
 *   PresenceSensor      — vehicle GPS tracking (present/not present based on movement)
 *
 * Custom attributes (populated from API metrics/states):
 *   odometer, fuelLevel, fuelRange, batterySOC, batteryRange,
 *   tirePressureFL, tirePressureFR, tirePressureRL, tirePressureRR,
 *   doorStatusDriver, doorStatusPassenger, doorStatusRearLeft, doorStatusRearRight,
 *   windowStatusDriver, windowStatusPassenger, windowStatusRearLeft, windowStatusRearRight,
 *   ignition, lockState, alarmStatus, outsideTemperature, oilLife,
 *   chargingStatus, plugStatus, chargingPower,
 *   latitude, longitude, speed, lastUpdated
 *
 * All parsing mirrors fordpass_handler.py — metric/state key names are preserved
 * so you can cross-reference the source easily.
 */

import groovy.json.JsonSlurper

metadata {
    definition(
        name:      "FordPass Vehicle",
        namespace: "fordpass-hubitat",
        author:    "Ported from marq24/ha-fordpass",
        description: "Ford / Lincoln connected vehicle — child of FordPass Connect app"
    ) {
        // --- Standard Hubitat capabilities ---
        capability "Lock"             // lock(), unlock(), attribute: lock (locked/unlocked)
        capability "Switch"           // on() = remote start, off() = cancel remote start
        capability "Refresh"          // refresh()
        capability "PresenceSensor"   // attribute: presence (present/not present)
        capability "TemperatureMeasurement"  // attribute: temperature (outside temp)

        // --- Commands ---
        command "remoteStart"
        command "cancelRemoteStart"
        command "honkAndFlash"
        command "requestStatusRefresh"   // ask the vehicle to push an update
        command "enableGuardMode"
        command "disableGuardMode"

        // --- Custom read-only attributes ---

        // Distance / range
        attribute "odometer",       "number"   // km or miles depending on region
        attribute "fuelLevel",      "number"   // percentage 0-100
        attribute "fuelRange",      "number"   // km or miles
        attribute "batterySOC",     "number"   // EV/PHEV: state of charge %
        attribute "batteryRange",   "number"   // EV/PHEV: electric range km or miles

        // Tires
        attribute "tirePressureFL", "number"   // Front Left
        attribute "tirePressureFR", "number"   // Front Right
        attribute "tirePressureRL", "number"   // Rear Left
        attribute "tirePressureRR", "number"   // Rear Right
        attribute "tirePressureUnit", "string"

        // Doors  (CLOSED / OPEN / AJAR)
        attribute "doorStatusDriver",      "string"
        attribute "doorStatusPassenger",   "string"
        attribute "doorStatusRearLeft",    "string"
        attribute "doorStatusRearRight",   "string"
        attribute "doorStatusHood",        "string"
        attribute "doorStatusTailgate",    "string"

        // Windows (CLOSED / OPEN)
        attribute "windowStatusDriver",    "string"
        attribute "windowStatusPassenger", "string"
        attribute "windowStatusRearLeft",  "string"
        attribute "windowStatusRearRight", "string"

        // Ignition / lock / alarm
        attribute "ignition",     "string"   // Off / On / Start
        attribute "lockState",    "string"   // LOCKED / PARTLY_LOCKED / UNLOCKED
        attribute "alarmStatus",  "string"

        // Fluids / engine
        attribute "oilLife",       "number"   // percentage
        attribute "engineCoolantTemp", "number"
        attribute "engineOilTemp",     "number"

        // EV / charging
        attribute "chargingStatus",     "string"   // NOT_READY / IN_PROGRESS / COMPLETE / PAUSED / SCHEDULED
        attribute "plugStatus",         "string"   // CONNECTED / DISCONNECTED / CHARGING
        attribute "chargingPower",      "number"   // kW
        attribute "targetSOC",          "number"   // %

        // GPS
        attribute "latitude",    "number"
        attribute "longitude",   "number"
        attribute "speed",       "number"
        attribute "heading",     "number"

        // Status
        attribute "lastUpdated", "string"
        attribute "deepSleepMode", "string"
    }

    preferences {
        input(name: "pressureUnit",    type: "enum",   title: "Tire pressure unit",
              options: ["PSI": "PSI", "kPa": "kPa", "BAR": "BAR"], defaultValue: "PSI")
        input(name: "distanceUnit",    type: "enum",   title: "Distance unit",
              options: ["km": "km", "miles": "miles"], defaultValue: "miles")
        input(name: "enableDebugLog",  type: "bool",   title: "Enable debug logging", defaultValue: false)
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed()  { logInfo("installed"); initialize() }
def updated()    { logInfo("updated");   initialize() }
def initialize() { logDebug("initialize") }

// ---------------------------------------------------------------------------
// Standard capability commands
// ---------------------------------------------------------------------------

def lock() {
    logInfo("lock()")
    if (parent.sendVehicleCommand("lock")) {
        sendEvent(name: "lock", value: "locked")
    }
}

def unlock() {
    logInfo("unlock()")
    if (parent.sendVehicleCommand("unlock")) {
        sendEvent(name: "lock", value: "unlocked")
    }
}

def on() {
    remoteStart()
}

def off() {
    cancelRemoteStart()
}

def refresh() {
    logInfo("refresh()")
    parent.pollVehicle()
}

// ---------------------------------------------------------------------------
// Custom commands
// ---------------------------------------------------------------------------

def remoteStart() {
    logInfo("remoteStart()")
    if (parent.sendVehicleCommand("remoteStart")) {
        sendEvent(name: "switch", value: "on")
    }
}

def cancelRemoteStart() {
    logInfo("cancelRemoteStart()")
    if (parent.sendVehicleCommand("cancelRemoteStart")) {
        sendEvent(name: "switch", value: "off")
    }
}

def honkAndFlash() {
    logInfo("honkAndFlash()")
    // Autonomic command name is 'startPanicCue'; duration 3 = DEFAULT in HONK_AND_FLASH enum
    parent.sendVehicleCommand("startPanicCue", [duration: 3])
}

def requestStatusRefresh() {
    logInfo("requestStatusRefresh()")
    // Tells the vehicle to push a fresh status update; can take up to 5 min
    parent.sendVehicleCommand("statusRefresh")
}

def enableGuardMode() {
    logInfo("enableGuardMode()")
    // Guard mode uses a separate Ford MPS API endpoint — not the Autonomic commands endpoint
    parent.sendGuardModeCommand("enable")
}

def disableGuardMode() {
    logInfo("disableGuardMode()")
    parent.sendGuardModeCommand("disable")
}

// ---------------------------------------------------------------------------
// Data parsing — called by the app after each poll
// ---------------------------------------------------------------------------

/**
 * Entry point for incoming vehicle data from the app's pollVehicle().
 * rawData is the parsed JSON Map from GET /api/expdashboard/v1/details/
 *
 * The JSON has these top-level sections (mirrors fordpass_handler.py ROOT_* constants):
 *   vehiclestatus.metrics   — sensor readings (odometer, fuel, tires, etc.)
 *   vehiclestatus.states    — discrete states (lock, door, ignition, etc.)
 *   vehiclestatus.events    — event log
 *   gps                     — location
 */
void parseVehicleData(Map rawData) {
    logDebug("parseVehicleData()")
    if (!rawData) { logWarn("parseVehicleData: null data"); return }

    try {
        def vehicleStatus = rawData.vehiclestatus ?: rawData

        parseMetrics(vehicleStatus.metrics ?: [:])
        parseStates(vehicleStatus.states   ?: [:])
        parseGps(rawData.gps               ?: vehicleStatus.gps ?: [:])

        sendEvent(name: "lastUpdated", value: new Date().toString())
    } catch (Exception e) {
        logError("parseVehicleData: ${e.message}")
    }
}

// ---------------------------------------------------------------------------
// Metrics parsing (fordpass_handler.py metric keys)
// ---------------------------------------------------------------------------

private void parseMetrics(def metrics) {
    if (!metrics) return

    // --- Odometer ---
    safeMetric(metrics, "odometer") { v ->
        sendEvent(name: "odometer", value: v as BigDecimal, unit: settings.distanceUnit ?: "miles")
    }

    // --- Fuel ---
    safeMetric(metrics, "fuelLevel") { v ->
        sendEvent(name: "fuelLevel", value: (v as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "%")
    }
    safeMetric(metrics, "dteForMilesHvb") { v ->
        sendEvent(name: "fuelRange", value: v as BigDecimal, unit: settings.distanceUnit ?: "miles")
    }

    // --- EV battery ---
    safeMetric(metrics, "xevBatteryStateOfCharge") { v ->
        sendEvent(name: "batterySOC", value: (v as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "%")
    }
    safeMetric(metrics, "xevBatteryRange") { v ->
        sendEvent(name: "batteryRange", value: v as BigDecimal, unit: settings.distanceUnit ?: "miles")
    }
    safeMetric(metrics, "xevPlugChargerStatus") { v ->
        sendEvent(name: "plugStatus", value: v?.toString())
    }
    safeMetric(metrics, "xevBatteryChargeDisplayStatus") { v ->
        sendEvent(name: "chargingStatus", value: v?.toString())
        // Mirror to switch so automations can key off it
    }
    safeMetric(metrics, "xevChargerPowertype") { v ->
        // power in kW — sometimes named differently
    }

    // --- Tires (key names from const_tags.py) ---
    String pUnit = settings.pressureUnit ?: "PSI"
    safeMetric(metrics, "tirePressureSystemStatus") { v ->
        // aggregate; individual values below
    }
    safeMetric(metrics, "tirePressureFrontLeft")  { v -> sendEvent(name: "tirePressureFL", value: convertPressure(v, pUnit), unit: pUnit) }
    safeMetric(metrics, "tirePressureFrontRight") { v -> sendEvent(name: "tirePressureFR", value: convertPressure(v, pUnit), unit: pUnit) }
    safeMetric(metrics, "tirePressureRearLeft")   { v -> sendEvent(name: "tirePressureRL", value: convertPressure(v, pUnit), unit: pUnit) }
    safeMetric(metrics, "tirePressureRearRight")  { v -> sendEvent(name: "tirePressureRR", value: convertPressure(v, pUnit), unit: pUnit) }
    sendEvent(name: "tirePressureUnit", value: pUnit)

    // --- Temperatures ---
    safeMetric(metrics, "ambientTemp") { v ->
        BigDecimal celsius = v as BigDecimal
        sendEvent(name: "temperature", value: celsius, unit: "°C")
        sendEvent(name: "outsideTemperature", value: celsius, unit: "°C")
    }
    safeMetric(metrics, "coolantTemp") { v ->
        sendEvent(name: "engineCoolantTemp", value: v as BigDecimal, unit: "°C")
    }
    safeMetric(metrics, "engineOilTemp") { v ->
        sendEvent(name: "engineOilTemp", value: v as BigDecimal, unit: "°C")
    }

    // --- Oil life ---
    safeMetric(metrics, "oilLifeRemaining") { v ->
        sendEvent(name: "oilLife", value: v as BigDecimal, unit: "%")
    }

    // --- Speed ---
    safeMetric(metrics, "vehicleSpeed") { v ->
        sendEvent(name: "speed", value: v as BigDecimal, unit: settings.distanceUnit == "miles" ? "mph" : "km/h")
    }
}

// ---------------------------------------------------------------------------
// States parsing
// ---------------------------------------------------------------------------

private void parseStates(def states) {
    if (!states) return

    // --- Lock ---
    safeState(states, "doorLockStatus") { v ->
        sendEvent(name: "lockState", value: v?.toString())
        // Map to Hubitat capability value
        String hubitatLock
        switch (v?.toString()?.toUpperCase()) {
            case "LOCKED":        hubitatLock = "locked";   break
            case "UNLOCKED":      hubitatLock = "unlocked"; break
            case "PARTLY_LOCKED": hubitatLock = "unlocked"; break  // treat partial as unlocked
            default:              hubitatLock = "unknown"
        }
        sendEvent(name: "lock", value: hubitatLock)
    }

    // --- Ignition ---
    safeState(states, "ignitionStatus") { v ->
        sendEvent(name: "ignition", value: v?.toString())
        // Remote start is "on" when ignition is active without a key
        sendEvent(name: "switch", value: (v?.toString()?.toUpperCase() == "START") ? "on" : "off")
    }

    // --- Doors ---
    safeState(states, "driverDoor")          { v -> sendEvent(name: "doorStatusDriver",    value: normaliseDoor(v)) }
    safeState(states, "passengerDoor")       { v -> sendEvent(name: "doorStatusPassenger", value: normaliseDoor(v)) }
    safeState(states, "leftRearDoor")        { v -> sendEvent(name: "doorStatusRearLeft",  value: normaliseDoor(v)) }
    safeState(states, "rightRearDoor")       { v -> sendEvent(name: "doorStatusRearRight", value: normaliseDoor(v)) }
    safeState(states, "hoodDoor")            { v -> sendEvent(name: "doorStatusHood",      value: normaliseDoor(v)) }
    safeState(states, "tailgateDoor")        { v -> sendEvent(name: "doorStatusTailgate",  value: normaliseDoor(v)) }

    // --- Windows ---
    safeState(states, "driverWindowPosition")    { v -> sendEvent(name: "windowStatusDriver",    value: normaliseWindow(v)) }
    safeState(states, "passengerWindowPosition") { v -> sendEvent(name: "windowStatusPassenger", value: normaliseWindow(v)) }
    safeState(states, "rearDriverWindowPos")     { v -> sendEvent(name: "windowStatusRearLeft",  value: normaliseWindow(v)) }
    safeState(states, "rearPassWindowPos")       { v -> sendEvent(name: "windowStatusRearRight", value: normaliseWindow(v)) }

    // --- Alarm ---
    safeState(states, "alarmStatus") { v ->
        sendEvent(name: "alarmStatus", value: v?.toString())
    }

    // --- Deep sleep ---
    safeState(states, "deepSleepStatus") { v ->
        sendEvent(name: "deepSleepMode", value: v?.toString())
    }
}

// ---------------------------------------------------------------------------
// GPS parsing
// ---------------------------------------------------------------------------

private void parseGps(def gps) {
    if (!gps) return

    def lat = gps.latitude  ?: gps.lat
    def lon = gps.longitude ?: gps.lon ?: gps.lng

    if (lat != null) {
        sendEvent(name: "latitude",  value: lat as BigDecimal)
        sendEvent(name: "longitude", value: lon as BigDecimal)
        // PresenceSensor — always "present" (vehicle is on your account)
        sendEvent(name: "presence", value: "present")
    }
    if (gps.heading != null) {
        sendEvent(name: "heading", value: gps.heading as BigDecimal)
    }
}

// ---------------------------------------------------------------------------
// Normalisation helpers
// ---------------------------------------------------------------------------

private String normaliseDoor(def raw) {
    switch (raw?.toString()?.toUpperCase()) {
        case "CLOSED":    return "closed"
        case "OPEN":      return "open"
        case "AJAR":      return "ajar"
        default:          return raw?.toString() ?: "unknown"
    }
}

private String normaliseWindow(def raw) {
    switch (raw?.toString()?.toUpperCase()) {
        case "CLOSED":    return "closed"
        case "OPEN":      return "open"
        default:          return raw?.toString() ?: "unknown"
    }
}

/**
 * Ford API returns tire pressure in kPa internally.
 * Convert to the user's preferred unit.
 */
private BigDecimal convertPressure(def kPaValue, String targetUnit) {
    BigDecimal kPa = kPaValue as BigDecimal
    switch (targetUnit?.toUpperCase()) {
        case "PSI":  return (kPa * 0.145038).setScale(1, BigDecimal.ROUND_HALF_UP)
        case "BAR":  return (kPa / 100).setScale(2, BigDecimal.ROUND_HALF_UP)
        default:     return kPa.setScale(1, BigDecimal.ROUND_HALF_UP)  // kPa
    }
}

// ---------------------------------------------------------------------------
// Safe metric / state extractors
// ---------------------------------------------------------------------------

/**
 * metrics may be a List of {key, value, status} objects or a flat Map.
 * Handle both shapes from the API response.
 */
private void safeMetric(def metrics, String key, Closure handler) {
    try {
        def raw = null
        if (metrics instanceof List) {
            def entry = metrics.find { it.key == key || it.name == key }
            raw = entry?.value
        } else if (metrics instanceof Map) {
            raw = metrics[key]?.value ?: metrics[key]
        }
        if (raw != null) {
            handler(raw)
        }
    } catch (Exception e) {
        logDebug("safeMetric(${key}): ${e.message}")
    }
}

/**
 * states may also be a List or Map — same dual-shape handling.
 */
private void safeState(def states, String key, Closure handler) {
    try {
        def raw = null
        if (states instanceof List) {
            def entry = states.find { it.key == key || it.name == key }
            raw = entry?.value
        } else if (states instanceof Map) {
            raw = states[key]?.value ?: states[key]
        }
        if (raw != null) {
            handler(raw)
        }
    } catch (Exception e) {
        logDebug("safeState(${key}): ${e.message}")
    }
}

// ---------------------------------------------------------------------------
// Logging
// ---------------------------------------------------------------------------

private void logDebug(String msg) { if (settings.enableDebugLog) log.debug "[FordPass Vehicle] ${msg}" }
private void logInfo(String msg)  { log.info  "[FordPass Vehicle] ${msg}" }
private void logWarn(String msg)  { log.warn  "[FordPass Vehicle] ${msg}" }
private void logError(String msg) { log.error "[FordPass Vehicle] ${msg}" }
