/**
 * FordPass Connect — Hubitat App
 *
 * Ported from marq24/ha-fordpass (Home Assistant custom integration).
 * Source: https://github.com/marq24/ha-fordpass
 *
 * Implements the multi-step OAuth2 PKCE flow used by the FordPass mobile app,
 * exchanges tokens with both the Ford Foundational API and the Autonomic API,
 * and creates/updates child FordPass Vehicle devices.
 *
 * Setup flow:
 *   1. mainPage      — status overview, entry point
 *   2. regionPage    — pick brand (Ford/Lincoln) + region
 *   3. authPage      — generate PKCE pair, show Ford login URL, accept redirect URL paste
 *   4. vinPage       — list vehicles on the account, pick one, create child device
 *
 * Token management:
 *   - Ford Foundational token  stored in state.fordToken / state.fordRefreshToken / state.fordTokenExpiry
 *   - Autonomic token          stored in state.autoToken / state.autoTokenExpiry
 *   - Tokens are refreshed automatically before every API call
 *   - A scheduled job (refreshTokens) runs every 4 minutes as a safety net
 */

import groovy.transform.Field
import java.security.MessageDigest
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// definition() must come immediately after imports — before any @Field declarations —
// so Hubitat's app pre-processor can inject it into scope correctly.
definition(
    name:        "FordPass Connect",
    namespace:   "fordpass-hubitat",
    author:      "Ported from marq24/ha-fordpass",
    description: "Connect Ford and Lincoln vehicles via the FordPass / Autonomic API",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    singleInstance: false
)

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

@Field String OAUTH_ID  = "4566605f-43a7-400a-946e-89cc9fdb0bd7"
@Field String CLIENT_ID = "09852200-05fd-41f6-8c21-d36d3497dc64"

@Field String FORD_FOUNDATIONAL_API = "https://api.foundational.ford.com/api"
@Field String FORD_VEHICLE_API      = "https://api.vehicle.ford.com/api"
@Field String FORD_MPS_API          = "https://api.mps.ford.com/api"
@Field String AUTONOMIC_ACCOUNT_URL = "https://accounts.autonomic.ai/v1"
@Field String AUTONOMIC_API_URL     = "https://api.autonomic.ai/v1"

// Default HTTP headers matching the FordPass Android app (okhttp/4.12.0)
@Field Map DEFAULT_HEADERS = [
    "Accept-Encoding": "gzip",
    "Connection":      "keep-alive",
    "User-Agent":      "okhttp/4.12.0",
]
@Field Map API_HEADERS    = ["Accept-Encoding": "gzip", "Connection": "keep-alive", "User-Agent": "okhttp/4.12.0", "Content-Type": "application/json"]
@Field Map LOGIN_HEADERS  = ["Accept-Encoding": "gzip", "Connection": "keep-alive", "User-Agent": "okhttp/4.12.0", "Content-Type": "application/x-www-form-urlencoded"]

/**
 * Region definitions.
 * Keys: app_id, locale, login_url, countrycode
 * Optional keys: sign_up_addon (Lincoln), redirect_schema (default "fordapp")
 *
 * Only actively confirmed regions are listed here. Add more from const.py as needed.
 */
@Field Map REGIONS = [
    "usa": [
        app_id:      "BFE8C5ED-D687-4C19-A5DD-F92CDFC4503A",
        locale:      "en-US",
        login_url:   "https://login.ford.com",
        countrycode: "USA"
    ],
    "can": [
        app_id:      "BFE8C5ED-D687-4C19-A5DD-F92CDFC4503A",
        locale:      "en-CA",
        login_url:   "https://login.ford.com",
        countrycode: "CAN"
    ],
    "mex": [
        app_id:      "BFE8C5ED-D687-4C19-A5DD-F92CDFC4503A",
        locale:      "es-MX",
        login_url:   "https://login.ford.com",
        countrycode: "MEX"
    ],
    "gbr": [
        app_id:      "667D773E-1BDC-4139-8AD0-2B16474E8DC7",
        locale:      "en-GB",
        login_url:   "https://login.ford.co.uk",
        countrycode: "GBR"
    ],
    "deu": [
        app_id:      "667D773E-1BDC-4139-8AD0-2B16474E8DC7",
        locale:      "de-DE",
        login_url:   "https://login.ford.de",
        countrycode: "DEU"
    ],
    "fra": [
        app_id:      "667D773E-1BDC-4139-8AD0-2B16474E8DC7",
        locale:      "fr-FR",
        login_url:   "https://login.ford.com",
        countrycode: "FRA"
    ],
    "nld": [
        app_id:      "667D773E-1BDC-4139-8AD0-2B16474E8DC7",
        locale:      "nl-NL",
        login_url:   "https://login.ford.com",
        countrycode: "NLD"
    ],
    "ita": [
        app_id:      "667D773E-1BDC-4139-8AD0-2B16474E8DC7",
        locale:      "it-IT",
        login_url:   "https://login.ford.com",
        countrycode: "ITA"
    ],
    "esp": [
        app_id:      "667D773E-1BDC-4139-8AD0-2B16474E8DC7",
        locale:      "es-ES",
        login_url:   "https://login.ford.com",
        countrycode: "ESP"
    ],
    "nor": [
        app_id:      "667D773E-1BDC-4139-8AD0-2B16474E8DC7",
        locale:      "no-NB",
        login_url:   "https://login.ford.no",
        countrycode: "NOR"
    ],
    "aus": [
        app_id:      "39CD6590-B1B9-42CB-BEF9-0DC1FDB96260",
        locale:      "en-AU",
        login_url:   "https://login.ford.com",
        countrycode: "AUS"
    ],
    "nzl": [
        app_id:      "39CD6590-B1B9-42CB-BEF9-0DC1FDB96260",
        locale:      "en-NZ",
        login_url:   "https://login.ford.com",
        countrycode: "NZL"
    ],
    "zaf": [
        app_id:      "71AA9ED7-B26B-4C15-835E-9F35CC238561",
        locale:      "en-ZA",
        login_url:   "https://login.ford.com",
        countrycode: "ZAF"
    ],
    "rest_of_europe": [
        app_id:      "667D773E-1BDC-4139-8AD0-2B16474E8DC7",
        locale:      "en-GB",
        login_url:   "https://login.ford.com",
        countrycode: "GBR"
    ],
    "lincoln_usa": [
        app_id:         "45133B88-0671-4AAF-B8D1-99E684ED4E45",
        locale:         "en-US",
        login_url:      "https://login.lincoln.com",
        countrycode:    "USA",
        sign_up_addon:  "Lincoln_",
        redirect_schema:"lincolnapp"
    ],
]

// ---------------------------------------------------------------------------
// Preferences (app pages)
// ---------------------------------------------------------------------------

preferences {
    page(name: "mainPage")
    page(name: "regionPage")
    page(name: "authPage")
    page(name: "tokenPage")
    page(name: "vinPage")
}

// ---------------------------------------------------------------------------
// Page definitions
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "FordPass Connect", install: true, uninstall: true) {

        boolean isConfigured = (state.fordToken != null && state.vin != null)

        section("Status") {
            if (isConfigured) {
                paragraph "Connected  |  VIN: ${state.vin}  |  Region: ${state.region}"
                paragraph "Last token refresh: ${state.lastTokenRefresh ?: 'Never'}"
            } else {
                paragraph "Not yet configured. Use the button below to set up your vehicle."
            }
        }

        section("Setup") {
            href(
                name:        "toRegionPage",
                title:       isConfigured ? "Re-authenticate / Change Region" : "Connect a Vehicle",
                description: "Step 1 of 3 — Select brand and region",
                page:        "regionPage"
            )
        }

        if (isConfigured) {
            section("Actions") {
                input(
                    name:         "pollNow",
                    type:         "button",
                    title:        "Poll Vehicle Now",
                    submitOnChange: true
                )
                input(
                    name:         "clearTokens",
                    type:         "button",
                    title:        "Clear Saved Tokens",
                    submitOnChange: true
                )
            }
            section("Child Devices") {
                paragraph "Vehicle device: FordPass Vehicle — ${state.vin}"
            }
        }

        section("Polling Interval") {
            input(
                name:         "pollIntervalMinutes",
                type:         "enum",
                title:        "Auto-refresh every",
                options:      ["5": "5 min", "10": "10 min", "15": "15 min", "30": "30 min", "0": "Disabled"],
                defaultValue: "5",
                required:     true
            )
        }

        section("Logging") {
            input(name: "enableDebugLog", type: "bool", title: "Enable debug logging", defaultValue: false)
        }
    }
}

def regionPage() {
    // Clear PKCE state and stale auth settings so authPage always generates a fresh pair.
    // settings.loginUrlDisplay persists the old authorize URL across sessions — if not cleared,
    // the user copies the old URL (old challenge) while state.codeVerifier holds a new verifier.
    state.remove("codeVerifier")
    state.remove("pkceChallenge")
    app.updateSetting("loginUrlDisplay", [value: "", type: "text"])
    app.updateSetting("redirectUrl",     [value: "", type: "text"])

    dynamicPage(name: "regionPage", title: "Step 1 — Brand & Region", nextPage: "authPage", uninstall: false) {
        section("Brand") {
            input(
                name:         "brand",
                type:         "enum",
                title:        "Brand",
                options:      ["ford": "Ford", "lincoln": "Lincoln"],
                defaultValue: "ford",
                required:     true,
                submitOnChange: true
            )
        }

        List regionOptions
        if (settings.brand == "lincoln") {
            regionOptions = [["lincoln_usa": "Lincoln USA"]]
        } else {
            regionOptions = [
                ["usa":            "United States"],
                ["can":            "Canada"],
                ["mex":            "Mexico"],
                ["gbr":            "United Kingdom"],
                ["deu":            "Germany"],
                ["fra":            "France"],
                ["nld":            "Netherlands"],
                ["ita":            "Italy"],
                ["esp":            "Spain"],
                ["nor":            "Norway"],
                ["aus":            "Australia"],
                ["nzl":            "New Zealand"],
                ["zaf":            "South Africa"],
                ["rest_of_europe": "Other European Countries"],
            ]
        }

        section("Region") {
            input(
                name:         "region",
                type:         "enum",
                title:        "Region",
                options:      regionOptions,
                defaultValue: "usa",
                required:     true
            )
        }

        section("Account") {
            input(name: "username", type: "email", title: "FordPass / Lincoln Way email", required: true)
        }

        section("Info") {
            paragraph(
                "On the next page you will get a link to the Ford/Lincoln login page. " +
                "After logging in, your browser will be redirected to a URL starting with " +
                "<b>fordapp://</b> (or <b>lincolnapp://</b>). " +
                "Copy the full redirect URL and paste it back into the next page."
            )
        }
    }
}

def authPage() {
    // Generate the PKCE pair ONCE per auth flow and persist both halves.
    // authPage() is called again when the user returns to paste the redirect URL —
    // regenerating here would overwrite state.codeVerifier and break PKCE verification.
    if (!state.codeVerifier || !state.pkceChallenge) {
        Map pkce = generatePkcePair()
        state.codeVerifier  = pkce.verifier
        state.pkceChallenge = pkce.challenge
        logDebug("authPage: generated new PKCE pair")
    } else {
        logDebug("authPage: reusing existing PKCE pair")
    }

    String regionKey = settings.region ?: "usa"
    Map regionCfg    = REGIONS[regionKey]
    String loginUrl  = regionCfg?.login_url  ?: "https://login.ford.com"
    String locale    = regionCfg?.locale     ?: "en-US"
    String appId     = regionCfg?.app_id     ?: ""
    String schema    = regionCfg?.redirect_schema ?: "fordapp"
    String addon     = regionCfg?.sign_up_addon   ?: ""
    String policy    = "B2C_1A_SignInSignUp_${addon}${locale}"
    String countryCode = regionCfg?.countrycode ?: "USA"

    String authorizeUrl = ""
    try {
        authorizeUrl = buildAuthorizeUrl(loginUrl, policy, CLIENT_ID, schema,
                                        state.pkceChallenge as String,
                                        appId, locale, countryCode)
    } catch (Exception e) {
        logError("authPage: could not build login URL: ${e.message}")
    }

    dynamicPage(name: "authPage", title: "Step 2 — Authorize FordPass", nextPage: "vinPage", uninstall: false) {
        section("Step 1 — Open the Ford login page in your browser") {
            paragraph(
                "<b>Copy the URL below</b> and open it in Safari or Chrome. " +
                "Do NOT use Hubitat's built-in browser — Ford's login page blocks embedded browsers."
            )
            // Use paragraph (not input) so this always reflects the freshly generated PKCE challenge.
            // An input() would cache its value in settings and show a stale URL on subsequent visits.
            paragraph("<b>Ford Login URL:</b><br><small>${authorizeUrl}</small>")
            paragraph "Region: ${regionKey}  |  Policy: ${policy}  |  App-Id: ${appId}"
        }
        section("Step 2 — Paste the redirect URL") {
            paragraph(
                "After logging in, your browser will try to open a URL starting with <b>${schema}://</b>. " +
                "The page will fail to load — that is expected. " +
                "Copy the full URL from your browser's address bar and paste it here."
            )
            input(name: "redirectUrl", type: "text", title: "Paste the full redirect URL here", required: true)
        }
    }
}

def vinPage() {
    // Exchange the authorization code for tokens, then list available VINs.
    String errorMsg = null
    Map vinOptions = [:]

    if (settings.redirectUrl && state.codeVerifier) {
        try {
            boolean ok = exchangeAuthCodeForTokens(settings.redirectUrl, state.codeVerifier, settings.region)
            if (ok) {
                vinOptions = fetchVehicleList()
            } else {
                errorMsg = "Token exchange failed. Check logs and try again from Step 1."
            }
        } catch (Exception e) {
            logError("vinPage: exception during token exchange: ${e.message}")
            errorMsg = "Exception during token exchange: ${e.message}"
        }
    } else {
        errorMsg = "No redirect URL captured. Go back and complete Step 2."
    }

    dynamicPage(name: "vinPage", title: "Step 3 — Select Vehicle", install: true, uninstall: false) {
        if (errorMsg) {
            section("Error") { paragraph "<b style='color:red'>${errorMsg}</b>" }
        } else if (vinOptions.isEmpty()) {
            section("No vehicles found") {
                paragraph "No vehicles were returned by the API. Make sure your account has at least one connected vehicle."
            }
        } else {
            section("Select your vehicle") {
                input(
                    name:     "selectedVin",
                    type:     "enum",
                    title:    "Vehicle",
                    options:  vinOptions,
                    required: true
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logDebug("installed()")
    initialize()
}

def updated() {
    logDebug("updated()")
    unschedule()
    initialize()

    if (settings.pollNow) {
        pollVehicle()
        app.updateSetting("pollNow", false)
    }
    if (settings.clearTokens) {
        clearAllTokens()
        app.updateSetting("clearTokens", false)
    }
}

def initialize() {
    if (settings.selectedVin) {
        state.vin = settings.selectedVin
        state.region = settings.region
        ensureChildDevice(state.vin)
    }

    scheduleTokenRefresh()
    schedulePolling()
}

def appButtonHandler(String buttonName) {
    switch (buttonName) {
        case "pollNow":
            pollVehicle()
            break
        case "clearTokens":
            clearAllTokens()
            break
    }
}

// ---------------------------------------------------------------------------
// PKCE helpers
// ---------------------------------------------------------------------------

/**
 * Generate a PKCE code_verifier (128 URL-safe chars) and code_challenge (S256).
 * Mirrors config_flow.py::generate_code_challenge().
 */
Map generatePkcePair() {
    // SecureRandom is blocked in Hubitat's sandbox. UUID.randomUUID() is backed
    // by a SecureRandom internally (via java.util.UUID) and is available here.
    // Four UUIDs give 128 hex chars (512 bits of entropy) — well above the PKCE minimum.
    String verifier = (1..4).collect { UUID.randomUUID().toString().replace("-", "") }.join("")

    MessageDigest digest = MessageDigest.getInstance("SHA-256")
    byte[] hashBytes = digest.digest(verifier.getBytes("UTF-8"))

    String challenge = hashBytes.encodeBase64().toString()
        .tr('+/', '-_')
        .replaceAll('=+$', '')

    return [verifier: verifier, challenge: challenge]
}

/**
 * Build the Ford OAuth2 authorize URL with PKCE parameters.
 * Equivalent to the URL the HA config flow shows to the user.
 */
/**
 * Build the Ford OAuth2 authorize URL with PKCE parameters.
 *
 * Matches generate_url() in config_flow.py exactly — parameter names, order,
 * and encoding must match what Ford's B2C tenant expects.
 *
 * Key points vs a generic OAuth2 URL:
 *  - redirect_uri is NOT percent-encoded (Ford's server expects the raw ://)
 *  - scope has a LEADING %20 space: "%20{clientId}%20openid"
 *  - ford_application_id and country_code are required Ford-specific params
 *  - max_age, ui_locales, language_code are also required
 */
String buildAuthorizeUrl(String loginUrl, String policy, String clientId,
                         String schema, String codeChallenge,
                         String appId, String locale, String countryCode) {
    // redirect_uri intentionally left un-encoded — matches Python source
    String redirectUri = "${schema}://userauthorized"

    return "${loginUrl}/${OAUTH_ID}/${policy}/oauth2/v2.0/authorize" +
        "?redirect_uri=${redirectUri}" +
        "&response_type=code" +
        "&max_age=3600" +
        "&code_challenge=${codeChallenge}" +
        "&code_challenge_method=S256" +
        "&scope=%20${clientId}%20openid" +   // leading %20 matches Python source
        "&client_id=${clientId}" +
        "&ui_locales=${locale}" +
        "&language_code=${locale}" +
        "&ford_application_id=${appId}" +
        "&country_code=${countryCode}"
}

// ---------------------------------------------------------------------------
// OAuth token exchange (mirrors fordpass_bridge.py::generate_tokens())
// ---------------------------------------------------------------------------

/**
 * Full token exchange pipeline:
 *   Step 1 — POST to the regional B2C token endpoint with the authorization code
 *   Step 2 — POST to Ford Foundational API (cat-with-b2c-access-token)
 *   Step 3 — Exchange Foundational token for Autonomic token
 *
 * Returns true on success, false on failure.
 * Stores tokens in state on success.
 */
boolean exchangeAuthCodeForTokens(String redirectUrl, String codeVerifier, String regionKey) {
    logDebug("exchangeAuthCodeForTokens()")

    // Extract authorization code from the redirect URL
    String code = extractQueryParam(redirectUrl, "code")
    if (!code) {
        logError("exchangeAuthCodeForTokens: no 'code' param in redirect URL: ${redirectUrl}")
        return false
    }
    logDebug("exchangeAuthCodeForTokens: extracted code (length ${code.length()})")

    Map regionCfg   = REGIONS[regionKey]
    String loginUrl = regionCfg.login_url
    String locale   = regionCfg.locale
    String addon    = regionCfg.sign_up_addon ?: ""
    String schema   = regionCfg.redirect_schema ?: "fordapp"
    String policy   = "B2C_1A_SignInSignUp_${addon}${locale}"

    // --- Step 1: B2C token endpoint ---
    String tokenEndpoint = "${loginUrl}/${OAUTH_ID}/${policy}/oauth2/v2.0/token"
    String b2cBody = [
        "client_id":     CLIENT_ID,
        "scope":         "${CLIENT_ID} openid",
        "redirect_uri":  "${schema}://userauthorized",
        "grant_type":    "authorization_code",
        "resource":      "",
        "code":          code,
        "code_verifier": codeVerifier,
    ].collect { k, v -> "${k}=${java.net.URLEncoder.encode(v as String, 'UTF-8')}" }.join("&")

    logDebug("exchangeAuthCodeForTokens: Step 1 — POST to ${tokenEndpoint}")
    Map b2cResponse = httpPostSync(tokenEndpoint, b2cBody, LOGIN_HEADERS)

    if (!b2cResponse || !b2cResponse.access_token) {
        logError("exchangeAuthCodeForTokens: Step 1 FAILED — response: ${b2cResponse}")
        return false
    }
    logDebug("exchangeAuthCodeForTokens: Step 1 OK — got B2C access_token")

    // --- Step 2: Ford Foundational API (cat-with-b2c-access-token) ---
    boolean step2ok = exchangeB2CTokenForFordToken(b2cResponse.access_token, regionCfg.app_id)
    if (!step2ok) { return false }

    // --- Step 3: Autonomic token (uses the Ford Foundational access_token) ---
    boolean step3ok = refreshAutoToken()
    if (!step3ok) {
        // Non-fatal: some commands only need the Ford token; log and continue
        logWarn("exchangeAuthCodeForTokens: Step 3 (Autonomic) FAILED — vehicle commands may not work")
    }

    state.lastTokenRefresh = new Date().toString()
    logInfo("exchangeAuthCodeForTokens: all tokens acquired successfully")
    return true
}

/**
 * Step 2: Exchange the B2C access_token for a Ford Foundational API token.
 * Mirrors fordpass_bridge.py::generate_tokens_part2()
 */
boolean exchangeB2CTokenForFordToken(String b2cAccessToken, String appId) {
    logDebug("exchangeB2CTokenForFordToken()")
    String url  = "${FORD_FOUNDATIONAL_API}/token/v2/cat-with-b2c-access-token"
    Map headers = API_HEADERS + ["Application-Id": appId]
    String body = JsonOutput.toJson([idpToken: b2cAccessToken])

    Map resp = httpPostSync(url, body, headers)
    if (!resp || !resp.access_token) {
        logError("exchangeB2CTokenForFordToken: FAILED — response: ${resp}")
        return false
    }

    storeFoundationalToken(resp)
    logDebug("exchangeB2CTokenForFordToken: OK")
    return true
}

/**
 * Refresh the Ford Foundational token using the stored refresh_token.
 * Mirrors fordpass_bridge.py::_request_token()
 */
boolean refreshFoundationalToken() {
    logDebug("refreshFoundationalToken()")
    if (!state.fordRefreshToken) {
        logError("refreshFoundationalToken: no refresh token stored — re-authentication required")
        return false
    }

    Map regionCfg = REGIONS[state.region ?: "usa"]
    String url    = "${FORD_FOUNDATIONAL_API}/token/v2/cat-with-refresh-token"
    Map headers   = API_HEADERS + ["Application-Id": regionCfg.app_id]
    String body   = JsonOutput.toJson([refresh_token: state.fordRefreshToken])

    Map resp = httpPostSync(url, body, headers)
    if (!resp || !resp.access_token) {
        logError("refreshFoundationalToken: FAILED — response: ${resp}")
        return false
    }

    storeFoundationalToken(resp)
    logDebug("refreshFoundationalToken: OK")
    return true
}

/**
 * Exchange the Ford Foundational access_token for an Autonomic API token.
 * Mirrors fordpass_bridge.py::_request_auto_token()
 *
 * The Autonomic token is used for vehicle commands (lock/unlock, remote start, etc.)
 */
boolean refreshAutoToken() {
    logDebug("refreshAutoToken()")
    if (!state.fordToken) {
        logError("refreshAutoToken: no Ford token available — cannot get Autonomic token")
        return false
    }

    String url  = "${AUTONOMIC_ACCOUNT_URL}/auth/oidc/token"
    Map headers = [
        "accept":       "*/*",
        "content-type": "application/x-www-form-urlencoded",
    ]
    String body = [
        "subject_token":      state.fordToken,
        "subject_issuer":     "fordpass",
        "client_id":          "fordpass-prod",
        "grant_type":         "urn:ietf:params:oauth:grant-type:token-exchange",
        "subject_token_type": "urn:ietf:params:oauth:token-type:jwt",
    ].collect { k, v -> "${k}=${java.net.URLEncoder.encode(v as String, 'UTF-8')}" }.join("&")

    Map resp = httpPostSync(url, body, headers)
    if (!resp || !resp.access_token) {
        logError("refreshAutoToken: FAILED — response: ${resp}")
        return false
    }

    long now = (long)(new Date().time / 1000)
    state.autoToken       = resp.access_token
    state.autoTokenExpiry = now + (resp.expires_in as long ?: 290L)

    logDebug("refreshAutoToken: OK — expires in ${resp.expires_in}s")
    return true
}

// ---------------------------------------------------------------------------
// Token storage helpers
// ---------------------------------------------------------------------------

private void storeFoundationalToken(Map tokenResp) {
    long now = (long)(new Date().time / 1000)
    state.fordToken         = tokenResp.access_token
    state.fordRefreshToken  = tokenResp.refresh_token
    state.fordTokenExpiry   = now + (tokenResp.expires_in as long ?: 290L)
    if (tokenResp.refresh_expires_in) {
        state.fordRefreshTokenExpiry = now + (tokenResp.refresh_expires_in as long)
    }
}

/**
 * Ensure both tokens are valid before making an API call.
 * Refreshes the Foundational token if it is expired (or within 30 s of expiry).
 * Refreshes the Autonomic token if it is missing or expired.
 *
 * Call this at the top of every method that makes an API request.
 * Returns false if tokens could not be refreshed (caller should abort).
 */
boolean ensureValidTokens() {
    long now = (long)(new Date().time / 1000)

    // Refresh Foundational token if expired (or close to it)
    if (!state.fordToken || !state.fordTokenExpiry || now >= (state.fordTokenExpiry as long) - 30) {
        logDebug("ensureValidTokens: Ford token expired or missing — refreshing")
        if (!refreshFoundationalToken()) { return false }
    }

    // Refresh Autonomic token if missing or expired
    if (!state.autoToken || !state.autoTokenExpiry || now >= (state.autoTokenExpiry as long) - 30) {
        logDebug("ensureValidTokens: Autonomic token expired or missing — refreshing")
        if (!refreshAutoToken()) {
            // Non-fatal — Ford data reads don't need the Autonomic token
            logWarn("ensureValidTokens: could not refresh Autonomic token")
        }
    }

    return true
}

void clearAllTokens() {
    logInfo("clearAllTokens(): clearing all stored tokens")
    state.remove("fordToken")
    state.remove("fordRefreshToken")
    state.remove("fordTokenExpiry")
    state.remove("fordRefreshTokenExpiry")
    state.remove("autoToken")
    state.remove("autoTokenExpiry")
    state.remove("codeVerifier")
    state.remove("lastTokenRefresh")
}

// ---------------------------------------------------------------------------
// Scheduled token refresh
// ---------------------------------------------------------------------------

void scheduleTokenRefresh() {
    // Refresh Ford token every 4 minutes as a safety net
    // (tokens expire after ~5 min; we refresh 30 s before expiry per ensureValidTokens,
    //  but this catches the case where Hubitat is idle)
    schedule("0 */4 * * * ?", "tokenRefreshJob")
}

void tokenRefreshJob() {
    logDebug("tokenRefreshJob()")
    if (state.fordRefreshToken) {
        refreshFoundationalToken()
        refreshAutoToken()
        state.lastTokenRefresh = new Date().toString()
    }
}

// ---------------------------------------------------------------------------
// Vehicle data polling
// ---------------------------------------------------------------------------

void schedulePolling() {
    String interval = settings.pollIntervalMinutes ?: "5"
    if (interval == "0") {
        logInfo("schedulePolling: polling disabled")
        return
    }
    schedule("0 */${interval} * * * ?", "pollVehicle")
    logInfo("schedulePolling: polling every ${interval} minutes")
}

void pollVehicle() {
    logDebug("pollVehicle()")
    if (!state.vin) {
        logWarn("pollVehicle: no VIN configured")
        return
    }

    if (!ensureValidTokens()) {
        logError("pollVehicle: could not refresh tokens — aborting poll")
        return
    }

    Map data = fetchVehicleStatus(state.vin)
    if (data == null) {
        logError("pollVehicle: fetchVehicleStatus returned null")
        return
    }

    def child = getChildDevice(childDeviceId(state.vin))
    if (child) {
        child.parseVehicleData(data)
    } else {
        logWarn("pollVehicle: child device not found for VIN ${state.vin}")
    }
}

// ---------------------------------------------------------------------------
// Ford Foundational API calls
// ---------------------------------------------------------------------------

/**
 * Fetch the vehicle list for the authenticated account.
 * Returns a Map of [vin: "Year Make Model (VIN)"] for use in a Hubitat enum input.
 *
 * Hubitat enum inputs require options as Map<value, displayLabel>.
 * A List<[value:, name:]> is NOT recognised — the literal key "value" ends up as the setting.
 *
 * Mirrors fordpass_bridge.py::req_vehicles()
 *  - Uses FORD_VEHICLE_API (api.vehicle.ford.com), NOT FORD_FOUNDATIONAL_API
 *  - Is a POST with body {"dashboardRefreshRequest": "All"}
 *  - Uses "auth-token" header, NOT "Authorization: Bearer"
 *  - Accepts both 200 and 207 as success
 */
Map fetchVehicleList() {
    logDebug("fetchVehicleList()")
    if (!ensureValidTokens()) { return [:] }

    Map regionCfg = REGIONS[state.region ?: settings.region ?: "usa"]
    Map headers   = API_HEADERS + [
        "auth-token":     state.fordToken,
        "Application-Id": regionCfg.app_id,
        "countryCode":    regionCfg.countrycode,
        "locale":         regionCfg.locale,
    ]
    String body = JsonOutput.toJson([dashboardRefreshRequest: "All"])

    Map vinOptions = [:]
    try {
        httpPost([
            uri:                "${FORD_VEHICLE_API}/expdashboard/v1/details/",
            headers:            headers,
            body:               body,
            requestContentType: "application/json",
            contentType:        "application/json",
        ]) { resp ->
            if (resp.status == 200 || resp.status == 207) {
                def data = resp.data
                def vehicles = data?.userVehicles?.vehicleDetails ?: []
                vehicles.each { v ->
                    String vin   = v.VIN ?: v.vin ?: ""
                    String year  = v.modelYear ?: ""
                    String make  = v.make      ?: "Ford"
                    String model = v.modelName ?: ""
                    String label = "${year} ${make} ${model} (${vin})".trim()
                    if (vin) { vinOptions[vin] = label }
                }
                logDebug("fetchVehicleList: HTTP ${resp.status} — found ${vinOptions.size()} vehicle(s)")
            } else {
                logError("fetchVehicleList: unexpected HTTP ${resp.status}")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        String errBody = ""
        try { errBody = e.response?.data?.toString() ?: "" } catch (Exception ignored) {}
        logError("fetchVehicleList: HTTP ${e.statusCode} — ${errBody ?: e.message}")
    } catch (Exception e) {
        logError("fetchVehicleList: ${e.message}")
    }

    return vinOptions
}

/**
 * Fetch full vehicle status / metrics for the given VIN.
 * Returns the raw parsed JSON Map (with "metrics" at the root), or null on failure.
 *
 * Uses the Autonomic telemetry API — NOT the Ford dashboard endpoint.
 * The response structure is:
 *   { "metrics": { ... }, "states": { ... }, "events": [...], ... }
 *
 * Auth: "Authorization: Bearer {autoToken}" (Autonomic token, NOT ford auth-token)
 * Mirrors fordpass_bridge.py::req_status() (do_as_post=False path)
 */
Map fetchVehicleStatus(String vin) {
    logDebug("fetchVehicleStatus(${vin})")
    if (!ensureValidTokens()) { return null }

    Map headers = [
        "Accept-Encoding": "gzip",
        "Connection":      "keep-alive",
        "User-Agent":      "okhttp/4.12.0",
        "Authorization":   "Bearer ${state.autoToken}",
    ]

    Map result = null
    try {
        httpGet([
            uri:         "${AUTONOMIC_API_URL}/telemetry/sources/fordpass/vehicles/${vin}",
            query:       ["lrdt": "01-01-1970 00:00:00"],
            headers:     headers,
            contentType: "application/json",
        ]) { resp ->
            if (resp.status == 200) {
                result = resp.data as Map
                logDebug("fetchVehicleStatus: HTTP 200 — metrics keys: ${(result?.metrics as Map)?.keySet()?.take(10)}")
            } else {
                logError("fetchVehicleStatus: HTTP ${resp.status}")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        String errBody = ""
        try { errBody = e.response?.data?.toString() ?: "" } catch (Exception ignored) {}
        logError("fetchVehicleStatus: HTTP ${e.statusCode} — ${errBody ?: e.message}")
    } catch (Exception e) {
        logError("fetchVehicleStatus: ${e.message}")
    }
    return result
}

// ---------------------------------------------------------------------------
// Autonomic vehicle commands (used by the child driver via parent reference)
// ---------------------------------------------------------------------------

/**
 * Send a command to the vehicle via the Autonomic API.
 *
 * The command name goes in the JSON body as "type" — NOT in the URL path.
 * URL: POST /v1/command/vehicles/{vin}/commands
 * Body: { "type": commandName, "properties": {extra}, "tags": {}, "wakeUp": true }
 *
 * Mirrors fordpass_bridge.py::__request_and_poll_command_autonomic()
 *
 * commandName: "lock", "unlock", "remoteStart", "cancelRemoteStart",
 *              "statusRefresh", "startPanicCue", etc.
 * props:       optional Map of extra fields merged into "properties" (default: empty)
 */
boolean sendVehicleCommand(String commandName, Map props = [:]) {
    logDebug("sendVehicleCommand(${commandName})")
    String vin = state.vin
    if (!vin) { logError("sendVehicleCommand: no VIN configured"); return false }
    if (!ensureValidTokens()) { logError("sendVehicleCommand: token refresh failed"); return false }

    Map headers = [
        "Content-Type":   "application/json",
        "Accept-Encoding": "gzip",
        "Connection":     "keep-alive",
        "User-Agent":     "okhttp/4.12.0",
        "Authorization":  "Bearer ${state.autoToken}",
    ]
    String url = "${AUTONOMIC_API_URL}/command/vehicles/${vin}/commands"
    Map bodyMap = [
        properties: props ?: [:],
        tags:       [:],
        type:       commandName,
        wakeUp:     true,
    ]
    String bodyJson = JsonOutput.toJson(bodyMap)

    boolean success = false
    try {
        httpPost([
            uri:                url,
            headers:            headers,
            body:               bodyJson,
            requestContentType: "application/json",
            contentType:        "application/json",
        ]) { resp ->
            if (resp.status >= 200 && resp.status < 300) {
                success = true
                logDebug("sendVehicleCommand(${commandName}): OK — HTTP ${resp.status}")
            } else {
                logError("sendVehicleCommand(${commandName}): HTTP ${resp.status} — ${resp.data}")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        String errBody = ""
        try { errBody = e.response?.data?.toString() ?: "" } catch (Exception ignored) {}
        logError("sendVehicleCommand(${commandName}): HTTP ${e.statusCode} — ${errBody ?: e.message}")
    } catch (Exception e) {
        logError("sendVehicleCommand(${commandName}): ${e.message}")
    }
    return success
}

/**
 * Enable or disable Guard Mode via the Ford MPS API.
 * Guard mode uses a completely different endpoint, auth format, and HTTP methods
 * from the Autonomic API — it cannot use sendVehicleCommand().
 *
 * action: "enable" (HTTP PUT) or "disable" (HTTP DELETE)
 *
 * Auth uses "auth-token: {fordToken}" — NOT "Authorization: Bearer".
 * Mirrors fordpass_bridge.py::set_guard_mode() / del_guard_mode()
 */
boolean sendGuardModeCommand(String action) {
    logDebug("sendGuardModeCommand(${action})")
    String vin = state.vin
    if (!vin) { logError("sendGuardModeCommand: no VIN configured"); return false }
    if (!ensureValidTokens()) { logError("sendGuardModeCommand: token refresh failed"); return false }

    Map regionCfg = REGIONS[state.region ?: "usa"]
    String url = "${FORD_MPS_API}/gmfi/v1/session"
    Map headers = [
        "Accept-Encoding": "gzip",
        "Connection":      "keep-alive",
        "User-Agent":      "okhttp/4.12.0",
        "Content-Type":    "application/json",
        "auth-token":      state.fordToken,      // Ford MPS uses auth-token, not Bearer
        "Application-Id":  regionCfg.app_id,
        "X-Vin":           vin,
    ]

    boolean success = false
    try {
        if (action == "enable") {
            httpPut([uri: url, headers: headers, contentType: "application/json"]) { resp ->
                success = (resp.status >= 200 && resp.status < 300)
                logDebug("sendGuardModeCommand(enable): HTTP ${resp.status}")
            }
        } else {
            httpDelete([uri: url, headers: headers, contentType: "application/json"]) { resp ->
                success = (resp.status >= 200 && resp.status < 300)
                logDebug("sendGuardModeCommand(disable): HTTP ${resp.status}")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        String errBody = ""
        try { errBody = e.response?.data?.toString() ?: "" } catch (Exception ignored) {}
        logError("sendGuardModeCommand(${action}): HTTP ${e.statusCode} — ${errBody ?: e.message}")
    } catch (Exception e) {
        logError("sendGuardModeCommand(${action}): ${e.class.simpleName} — ${e.message}")
    }
    return success
}

// ---------------------------------------------------------------------------
// Child device management
// ---------------------------------------------------------------------------

private String childDeviceId(String vin) { return "fordpass-${vin}" }

private void ensureChildDevice(String vin) {
    String devId = childDeviceId(vin)
    def existing = getChildDevice(devId)
    if (!existing) {
        logInfo("ensureChildDevice: creating child device for VIN ${vin}")
        addChildDevice(
            "fordpass-hubitat",         // namespace (must match driver definition)
            "FordPass Vehicle",         // driver name
            devId,
            null,
            [
                name:  "FordPass Vehicle",
                label: "Ford Vehicle ${vin}",
                isComponent: false,
            ]
        )
    } else {
        logDebug("ensureChildDevice: child device already exists for VIN ${vin}")
    }
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

/**
 * Synchronous HTTP POST.
 * Returns the parsed JSON response body as a Map/List, or null on failure.
 * Used during the setup flow where async is not needed.
 */
private Map httpPostSync(String url, String body, Map headers) {
    Map result = null
    try {
        httpPost([
            uri:                url,
            headers:            headers,
            body:               body,
            // requestContentType tells Hubitat how to encode/send the body.
            // contentType tells Hubitat how to PARSE the response.
            // These two are independent — Ford/Autonomic always respond with JSON
            // regardless of whether we POST form-encoded or JSON data.
            // Check both title-case and lowercase keys since callers may use either.
            requestContentType: (headers["Content-Type"] ?: headers["content-type"]) ?: "application/json",
            contentType:        "application/json",
        ]) { resp ->
            if (resp.data != null) {
                result = resp.data instanceof Map ? resp.data as Map : null
            }
            logDebug("httpPostSync ${url}: HTTP ${resp.status}")
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        // Log the response body — critical for diagnosing 400/401 errors from Ford/Autonomic
        String errBody = ""
        try { errBody = e.response?.data?.toString() ?: "" } catch (Exception ignored) {}
        logError("httpPostSync ${url}: HTTP ${e.statusCode} — body: ${errBody ?: e.message}")
        // Some Ford endpoints return non-200 with valid JSON — try to parse anyway
        try { result = new JsonSlurper().parseText(errBody ?: "{}") as Map }
        catch (Exception ignored) {}
    } catch (Exception e) {
        logError("httpPostSync ${url}: ${e.class.simpleName} — ${e.message}")
    }
    return result
}

/**
 * Extract a single query-parameter value from a URL string.
 * parse_qs equivalent.
 */
private String extractQueryParam(String url, String paramName) {
    try {
        String query = url.contains("?") ? url.split("\\?", 2)[1] : url
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2)
            if (kv[0] == paramName) {
                return java.net.URLDecoder.decode(kv[1], "UTF-8")
            }
        }
    } catch (Exception e) {
        logError("extractQueryParam: ${e.message}")
    }
    return null
}

// ---------------------------------------------------------------------------
// Logging
// ---------------------------------------------------------------------------

private void logDebug(String msg) { if (settings.enableDebugLog) log.debug "[FordPass] ${msg}" }
private void logInfo(String msg)  { log.info  "[FordPass] ${msg}" }
private void logWarn(String msg)  { log.warn  "[FordPass] ${msg}" }
private void logError(String msg) { log.error "[FordPass] ${msg}" }
