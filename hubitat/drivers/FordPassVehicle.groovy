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
        command "preconditionStart"      // start cabin preconditioning
        command "preconditionExtend"     // extend active preconditioning session
        command "preconditionStop"       // stop preconditioning

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

def preconditionStart() {
    logInfo("preconditionStart()")
    // Mach-E and RCC-capable vehicles use the Ford RCC API, not the Autonomic commands endpoint
    parent.sendRccCommand("On")
}

def preconditionExtend() {
    logInfo("preconditionExtend()")
    // Extending just re-sends the On command with current preferences
    parent.sendRccCommand("On")
}

def preconditionStop() {
    logInfo("preconditionStop()")
    parent.sendRccCommand("Off")
}

// ---------------------------------------------------------------------------
// Data parsing — called by the app after each poll
// ---------------------------------------------------------------------------

/**
 * Entry point for incoming vehicle data from the app's pollVehicle().
 *
 * Actual API structure (mirrors fordpass_handler.py ROOT_* constants):
 *   rawData.metrics  — Map<String, {value, updateTime}> for scalars,
 *                      List<{value, vehicleDoor/vehicleWheel/...}> for arrays
 *   rawData.states   — only deviceConnectivity / commandPreclusion (rarely useful)
 *   rawData.metrics.position.value.location — GPS {lat, lon, alt}
 *
 * NOTE: there is NO "vehiclestatus" wrapper — metrics is at the root.
 */
void parseVehicleData(Map rawData) {
    logDebug("parseVehicleData()")
    if (!rawData) { logWarn("parseVehicleData: null data"); return }

    try {
        def metrics = rawData.metrics ?: [:]
        parseMetrics(metrics)
        sendEvent(name: "lastUpdated", value: new Date().toString())
    } catch (Exception e) {
        logError("parseVehicleData: ${e.message}")
    }
}

// ---------------------------------------------------------------------------
// Metrics parsing
// ---------------------------------------------------------------------------

private void parseMetrics(def metrics) {
    if (!metrics) return

    // --- Scalar metrics: metrics[key] = { "value": <scalar>, "updateTime": "..." } ---

    // Ford API always returns distances in kilometres — convert if user prefers miles
    safeVal(metrics, "odometer")         { v -> sendEvent(name: "odometer",     value: convertDistance(v), unit: settings.distanceUnit ?: "miles") }

    safeVal(metrics, "fuelLevel")        { v -> sendEvent(name: "fuelLevel",    value: (v as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "%") }
    safeVal(metrics, "fuelRange")        { v -> sendEvent(name: "fuelRange",    value: convertDistance(v), unit: settings.distanceUnit ?: "miles") }

    safeVal(metrics, "xevBatteryStateOfCharge") { v -> sendEvent(name: "batterySOC",   value: (v as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "%") }
    safeVal(metrics, "xevBatteryRange")  { v -> sendEvent(name: "batteryRange", value: convertDistance(v), unit: settings.distanceUnit ?: "miles") }
    safeVal(metrics, "xevPlugChargerStatus")          { v -> sendEvent(name: "plugStatus",     value: v?.toString()) }
    safeVal(metrics, "xevBatteryChargeDisplayStatus") { v -> sendEvent(name: "chargingStatus", value: v?.toString()) }

    safeVal(metrics, "ignitionStatus")   { v ->
        sendEvent(name: "ignition", value: v?.toString())
        sendEvent(name: "switch", value: (v?.toString()?.toUpperCase() in ["START", "RUN"]) ? "on" : "off")
    }
    safeVal(metrics, "alarmStatus")      { v -> sendEvent(name: "alarmStatus",       value: v?.toString()) }
    safeVal(metrics, "deepSleepStatus")  { v -> sendEvent(name: "deepSleepMode",     value: v?.toString()) }

    safeVal(metrics, "oilLifeRemaining") { v -> sendEvent(name: "oilLife",           value: v as BigDecimal, unit: "%") }
    safeVal(metrics, "ambientTemp")      { v -> sendEvent(name: "temperature",        value: v as BigDecimal, unit: "°C") }
    safeVal(metrics, "engineOilTemp")    { v -> sendEvent(name: "engineOilTemp",      value: v as BigDecimal, unit: "°C") }
    safeVal(metrics, "coolantTemp")      { v -> sendEvent(name: "engineCoolantTemp",  value: v as BigDecimal, unit: "°C") }
    safeVal(metrics, "vehicleSpeed")     { v -> sendEvent(name: "speed",              value: v as BigDecimal) }

    // --- Array metrics ---

    // doorLockStatus: [{value:"LOCKED|UNLOCKED", vehicleDoor:"FRONT_LEFT|...", vehicleSide:"LH|RH"}, ...]
    parseDoorLockArray(metrics.doorLockStatus)

    // doorStatus: [{value:"CLOSED|OPEN|INVALID", vehicleDoor:"...", vehicleSide:"..."}, ...]
    parseDoorStatusArray(metrics.doorStatus)

    // windowStatus: [{value:{doubleRange:{lowerBound:0.0,upperBound:0.0}}, vehicleWindow:"...", vehicleSide:"..."}, ...]
    parseWindowStatusArray(metrics.windowStatus)

    // tirePressure: [{value:<kPa>, vehicleWheel:"FRONT_LEFT|FRONT_RIGHT|REAR_LEFT|REAR_RIGHT"}, ...]
    parseTirePressureArray(metrics.tirePressure)

    // GPS: metrics.position.value.location → {lat, lon, alt}
    parsePosition(metrics.position?.value?.location)
}

private void parseDoorLockArray(def lockList) {
    if (!lockList) return
    try {
        int total = 0, locked = 0
        lockList.each { entry ->
            def val = entry?.value?.toString()?.toUpperCase()
            if (val != null) { total++; if (val == "LOCKED") locked++ }
        }
        if (total == 0) return
        String lockState
        if (locked >= total)    { lockState = "LOCKED";        sendEvent(name: "lock", value: "locked") }
        else if (locked > 0)    { lockState = "PARTLY_LOCKED"; sendEvent(name: "lock", value: "unlocked") }
        else                    { lockState = "UNLOCKED";       sendEvent(name: "lock", value: "unlocked") }
        sendEvent(name: "lockState", value: lockState)
    } catch (Exception e) { logDebug("parseDoorLockArray: ${e.message}") }
}

private void parseDoorStatusArray(def doorList) {
    if (!doorList) return
    try {
        doorList.each { entry ->
            def val  = entry?.value?.toString()?.toUpperCase()
            def door = entry?.vehicleDoor?.toString()?.toUpperCase()
            def side = entry?.vehicleSide?.toString()?.toUpperCase()
            String norm = normaliseDoor(val)

            if      (door == "FRONT_LEFT"  || (door == "UNSPECIFIED_FRONT" && side == "LH")) sendEvent(name: "doorStatusDriver",    value: norm)
            else if (door == "FRONT_RIGHT" || (door == "UNSPECIFIED_FRONT" && side == "RH")) sendEvent(name: "doorStatusPassenger", value: norm)
            else if (door == "REAR_LEFT"   || (door == "UNSPECIFIED_REAR"  && side == "LH")) sendEvent(name: "doorStatusRearLeft",  value: norm)
            else if (door == "REAR_RIGHT"  || (door == "UNSPECIFIED_REAR"  && side == "RH")) sendEvent(name: "doorStatusRearRight", value: norm)
            else if (door == "HOOD")                                                          sendEvent(name: "doorStatusHood",      value: norm)
            else if (door in ["TAILGATE","TRUNK","LIFTGATE"])                                 sendEvent(name: "doorStatusTailgate",  value: norm)
        }
    } catch (Exception e) { logDebug("parseDoorStatusArray: ${e.message}") }
}

private void parseWindowStatusArray(def windowList) {
    if (!windowList) return
    try {
        windowList.each { entry ->
            def window = entry?.vehicleWindow?.toString()?.toUpperCase()
            def side   = entry?.vehicleSide?.toString()?.toUpperCase()
            def dr     = entry?.value?.doubleRange
            boolean open = dr && (dr.lowerBound != 0.0 || dr.upperBound != 0.0)
            String norm  = open ? "open" : "closed"

            if      (window == "FRONT_LEFT"  || (window?.contains("FRONT") && side == "LH")) sendEvent(name: "windowStatusDriver",    value: norm)
            else if (window == "FRONT_RIGHT" || (window?.contains("FRONT") && side == "RH")) sendEvent(name: "windowStatusPassenger", value: norm)
            else if (window == "REAR_LEFT"   || (window?.contains("REAR")  && side == "LH")) sendEvent(name: "windowStatusRearLeft",  value: norm)
            else if (window == "REAR_RIGHT"  || (window?.contains("REAR")  && side == "RH")) sendEvent(name: "windowStatusRearRight", value: norm)
        }
    } catch (Exception e) { logDebug("parseWindowStatusArray: ${e.message}") }
}

private void parseTirePressureArray(def tireList) {
    if (!tireList) return
    try {
        String pUnit = settings.pressureUnit ?: "PSI"
        tireList.each { entry ->
            def wheel = entry?.vehicleWheel?.toString()?.toUpperCase()
            def val   = entry?.value
            if (val == null) return
            BigDecimal converted = convertPressure(val, pUnit)

            if      (wheel == "FRONT_LEFT")  sendEvent(name: "tirePressureFL", value: converted, unit: pUnit)
            else if (wheel == "FRONT_RIGHT") sendEvent(name: "tirePressureFR", value: converted, unit: pUnit)
            else if (wheel == "REAR_LEFT")   sendEvent(name: "tirePressureRL", value: converted, unit: pUnit)
            else if (wheel == "REAR_RIGHT")  sendEvent(name: "tirePressureRR", value: converted, unit: pUnit)
        }
        sendEvent(name: "tirePressureUnit", value: pUnit)
    } catch (Exception e) { logDebug("parseTirePressureArray: ${e.message}") }
}

private void parsePosition(def location) {
    if (!location) return
    try {
        def lat = location.lat ?: location.latitude
        def lon = location.lon ?: location.longitude
        if (lat != null && lon != null) {
            sendEvent(name: "latitude",  value: lat as BigDecimal)
            sendEvent(name: "longitude", value: lon as BigDecimal)
            sendEvent(name: "presence",  value: "present")
        }
        if (location.alt != null) {
            // altitude available but no attribute for it; log for debugging
            logDebug("parsePosition: alt=${location.alt}")
        }
    } catch (Exception e) { logDebug("parsePosition: ${e.message}") }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Scalar metric extractor: metrics[key] = { "value": <v>, ... } */
private void safeVal(def metrics, String key, Closure handler) {
    try {
        def raw = metrics[key]?.value
        if (raw != null) handler(raw)
    } catch (Exception e) { logDebug("safeVal(${key}): ${e.message}") }
}

private String normaliseDoor(def raw) {
    switch (raw?.toString()?.toUpperCase()) {
        case "CLOSED":  return "closed"
        case "OPEN":    return "open"
        case "AJAR":    return "ajar"
        default:        return raw?.toString()?.toLowerCase() ?: "unknown"
    }
}

/**
 * Ford API returns distances in kilometres.
 * Convert to miles when the driver preference is set to "miles".
 */
private BigDecimal convertDistance(def kmValue) {
    BigDecimal km = kmValue as BigDecimal
    if ((settings.distanceUnit ?: "miles") == "miles") {
        return (km / 1.609344).setScale(1, BigDecimal.ROUND_HALF_UP)
    }
    return km.setScale(1, BigDecimal.ROUND_HALF_UP)
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
// Logging
// ---------------------------------------------------------------------------

private void logDebug(String msg) { if (settings.enableDebugLog) log.debug "[FordPass Vehicle] ${msg}" }
private void logInfo(String msg)  { log.info  "[FordPass Vehicle] ${msg}" }
private void logWarn(String msg)  { log.warn  "[FordPass Vehicle] ${msg}" }
private void logError(String msg) { log.error "[FordPass Vehicle] ${msg}" }
