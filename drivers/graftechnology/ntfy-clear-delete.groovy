metadata {
    definition(name: "NTFY Clear/Delete Driver", namespace: "graftechnology", author: "Graf Technology, LLC", version: "1.0.0") {
        capability "Notification"
        attribute "lastClearDeleteStatus", "string"
        attribute "lastClearDeleteTime", "string"
    }
}

preferences {
    input name: "ntfyProtocol", type: "enum", title: "Protocol", options: ["https", "http"], defaultValue: "https", required: true
    input name: "ntfyHost", type: "text", title: "NTFY Host (e.g., ntfy.sh or yourdomain.com)", defaultValue: "ntfy.sh", required: true
    input name: "ntfyUsername", type: "text", title: "Username (for self-hosted servers with auth or NTFY Pro)", required: false
    input name: "ntfyPassword", type: "password", title: "Password (for self-hosted servers with auth or NTFY Pro)", required: false
    input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: true
}

void installed() {
    logDebug "NTFY Clear/Delete Driver installed"
    log.info "Device Notification Syntax: {\"command\":\"clear|delete\", \"topic\":\"yourtopic\", \"sequenceid\":\"yoursequenceid\"}"
    initializeState()
}

void updated() {
    logDebug "NTFY Clear/Delete Driver updated"
    log.info "Device Notification Syntax: {\"command\":\"clear|delete\", \"topic\":\"yourtopic\", \"sequenceid\":\"yoursequenceid\"}"
    initializeState()
}

void initialize() {
    logDebug "NTFY Clear/Delete Driver initialized"
    log.info "Device Notification Syntax: {\"command\":\"clear|delete\", \"topic\":\"yourtopic\", \"sequenceid\":\"yoursequenceid\"}"
    initializeState()
}

void initializeState() {
    sendEvent(name: "lastClearDeleteStatus", value: "Not sent")
    sendEvent(name: "lastClearDeleteTime", value: "Never")
    logDebug "State initialized"
}

void deviceNotification(String msg) {
    logDebug "deviceNotification called with message: '${msg}'"

    if (!msg || msg.trim().isEmpty()) {
        def errorMsg = "Clear/Delete failed - Message cannot be empty"
        log.error errorMsg
        updateClearDeleteState("Failed", errorMsg)
        return
    }

    def json
    try {
        json = groovy.json.JsonSlurper.newInstance().parseText(msg)
    } catch (Exception e) {
        def errorMsg = "Clear/Delete failed - Invalid JSON: ${e.message}"
        log.error errorMsg
        updateClearDeleteState("Failed", errorMsg)
        return
    }

    def command = json.command?.toLowerCase()
    def topic = json.topic
    def sequenceId = json.sequenceid

    if (!(command in ["clear", "delete"])) {
        def errorMsg = "Clear/Delete failed - Command must be 'clear' or 'delete'"
        log.error errorMsg
        updateClearDeleteState("Failed", errorMsg)
        return
    }

    if (!topic || !topic.matches(/^[a-zA-Z0-9_-]+$/)) {
        def errorMsg = "Clear/Delete failed - Topic can only contain letters, numbers, underscores, and hyphens"
        log.error errorMsg
        updateClearDeleteState("Failed", errorMsg)
        return
    }

    if (!sequenceId) {
        def errorMsg = "Clear/Delete failed - Sequence ID cannot be empty"
        log.error errorMsg
        updateClearDeleteState("Failed", errorMsg)
        return
    }

    def headers = [:]
    if (ntfyUsername && ntfyUsername.trim() && ntfyPassword && ntfyPassword.trim()) {
        def credentials = "${ntfyUsername.trim()}:${ntfyPassword}".bytes.encodeBase64().toString()
        headers["Authorization"] = "Basic ${credentials}"
    }

    def uri
    def method
    def params

    if (command == "clear") {
        uri = "${ntfyProtocol}://${ntfyHost.trim()}/${topic.trim()}/${sequenceId}/clear"
        method = "PUT"
        params = [
            uri: uri,
            headers: headers,
            contentType: "text/plain",
            timeout: 30
        ]
    } else if (command == "delete") {
        uri = "${ntfyProtocol}://${ntfyHost.trim()}/${topic.trim()}/${sequenceId}"
        method = "DELETE"
        params = [
            uri: uri,
            headers: headers,
            contentType: "text/plain",
            timeout: 30
        ]
    }

    logDebug "Sending ${command} request to: ${uri} (method: ${method})"
    logDebug "Headers: ${headers}"

    try {
        if (method == "PUT") {
            httpPut(params) { resp ->
                logDebug "HTTP Response - Status: ${resp.status}, Headers: ${resp.headers}"
                if (resp.status >= 200 && resp.status < 300) {
                    def successMsg = "${command.capitalize()} command sent successfully (HTTP ${resp.status})"
                    logDebug successMsg
                    updateClearDeleteState("Success", successMsg)
                } else {
                    def errorMsg = "${command.capitalize()} failed - Server returned HTTP ${resp.status}"
                    log.warn errorMsg
                    updateClearDeleteState("Failed", errorMsg)
                }
            }
        } else if (method == "DELETE") {
            httpDelete(params) { resp ->
                logDebug "HTTP Response - Status: ${resp.status}, Headers: ${resp.headers}"
                if (resp.status >= 200 && resp.status < 300) {
                    def successMsg = "${command.capitalize()} command sent successfully (HTTP ${resp.status})"
                    logDebug successMsg
                    updateClearDeleteState("Success", successMsg)
                } else {
                    def errorMsg = "${command.capitalize()} failed - Server returned HTTP ${resp.status}"
                    log.warn errorMsg
                    updateClearDeleteState("Failed", errorMsg)
                }
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        def errorMsg = "${command.capitalize()} failed - HTTP ${e.statusCode}: ${e.message}"
        log.error errorMsg
        updateClearDeleteState("Failed", errorMsg)
    } catch (Exception e) {
        def errorMsg = "${command.capitalize()} failed - Unexpected error: ${e.message}"
        log.error errorMsg
        logDebug "Full exception details: ${e}"
        updateClearDeleteState("Failed", errorMsg)
    }
}

private void updateClearDeleteState(String status, String details) {
    def timestamp = new Date().format('yyyy-MM-dd HH:mm:ss')
    sendEvent(name: "lastClearDeleteStatus", value: status)
    sendEvent(name: "lastClearDeleteTime", value: timestamp)
    logDebug "Updated clear/delete state - Status: ${status}, Time: ${timestamp}, Details: ${details}"
}

private void logDebug(msg) {
    if (logEnable) log.debug msg
}
